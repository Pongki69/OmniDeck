package com.remotegamestreamer.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.remotegamestreamer.client.audio.AudioPlayer
import com.remotegamestreamer.client.decoder.FrameReassembler
import com.remotegamestreamer.client.decoder.VideoRenderSurface
import com.remotegamestreamer.client.input.KeyboardSender
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.input.PhysicalGamepadState
import com.remotegamestreamer.client.input.TouchHudOverlay
import com.remotegamestreamer.client.input.UdpInputSender
import com.remotegamestreamer.client.net.Protocol
import com.remotegamestreamer.client.net.SessionClient
import com.remotegamestreamer.client.net.SessionException
import java.net.DatagramPacket
import java.net.SocketException

/** Full-screen session: video under the translucent HUD. Ends whenever the app leaves the foreground. */
class StreamActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var video: VideoRenderSurface
    private lateinit var hud: TouchHudOverlay
    private lateinit var layoutModel: HudLayout
    private lateinit var streamRoot: FrameLayout
    private var liveLayoutTools: View? = null
    private val physicalGamepad = PhysicalGamepadState()

    private var wifiLock: WifiManager.WifiLock? = null
    private var session: SessionClient? = null
    private var sender: UdpInputSender? = null
    @Volatile private var keyboard: KeyboardSender? = null
    private var keyboardDialog: AlertDialog? = null
    private var sensitivityDialog: AlertDialog? = null
    private var audio: AudioPlayer? = null
    private var videoThread: Thread? = null
    @Volatile private var closing = false
    private var physicalR3WasDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        settings = AppSettings(this)
        video = VideoRenderSurface(this).apply { onDecoderError = { fail(it) } }
        video.setDisplayScale(settings.displayScalePercent)
        video.setStretchToFill(settings.stretchToFill)
        layoutModel = settings.layout
        hud = TouchHudOverlay(this, layoutModel, settings.stickLocked, settings.cameraSensitivityPercent, settings.cameraSensitivityYPercent)
        hud.setTouchDragCameraEnabled(settings.touchDragCamera)
        hud.onCameraActiveChanged = { active ->
            runOnUiThread { Toast.makeText(this, if (active) "Camera control on" else "Camera control paused", Toast.LENGTH_SHORT).show() }
        }
        hud.onKeyboardRequested = ::showKeyboardDialog
        hud.onCameraSensitivityRequested = ::showCameraSensitivityDialog
        hud.onLayoutEditRequested = ::showLiveLayoutEditor

        streamRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(streamRoot)
        hideSystemBars()
        acquireWifiLock()

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val pin = intent.getIntExtra(EXTRA_PIN, 0)
        Thread({ connect(host, pin) }, "Connect").start()
    }

    private fun connect(host: String, pin: Int) {
        try {
            val client = SessionClient(host)
            val request = settings.streamRequest()
            val welcome = client.connect(pin, request, settings)

            synchronized(this) {
                if (closing) {
                    client.close()
                    return
                }
                session = client
                video.configure(welcome.isHevc, welcome.width, welcome.height, welcome.fps)
                runOnUiThread { window.attributes = window.attributes.apply { preferredRefreshRate = welcome.fps.toFloat() } }
                hud.post {
                    hud.touchpadAvailable = welcome.mouseEnabled
                    hud.setStreamConnected(true)
                }

                sender = UdpInputSender(client.control, welcome.token, client.keys.clientToHost) { state ->
                    if (settings.touchDragCamera) {
                        val r3Down = physicalGamepad.isRightStickClickPressed()
                        if (r3Down && !physicalR3WasDown) hud.toggleCameraActive()
                        physicalR3WasDown = r3Down
                        val (rightX, rightY) = physicalGamepad.rightStickAxes()
                        hud.addPhysicalStickCamera(rightX, rightY)
                    } else {
                        physicalR3WasDown = false
                    }
                    hud.sample(state)
                    physicalGamepad.merge(state, includeRightStick = !settings.touchDragCamera)
                }.also { it.start() }
                if (welcome.mouseEnabled) keyboard = KeyboardSender(client.control, welcome.token, client.keys, ::onKeyboardResult)
                if (request.sound) audio = AudioPlayer(client.audio, client.host, welcome.sampleRate, client.keys.audio).also { it.start() }
                videoThread = Thread({ receiveVideo(client) }, "VideoReceive").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        } catch (e: SessionException) {
            fail(e.message ?: "Connection failed")
        } catch (e: Exception) {
            fail("Connection failed: ${e.message}")
        }
    }

    /**
     * Text goes to whatever is focused on the PC, so click the field with the Pad button first.
     * The text is hidden by default, kept out of the phone keyboard's learning, and never saved.
     */
    private fun showKeyboardDialog() {
        val sender = keyboard ?: return
        if (keyboardDialog?.isShowing == true) return

        val hiddenText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visibleText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val field = EditText(this).apply {
            hint = "Type or paste text for the focused PC window"
            inputType = hiddenText
            filters = arrayOf(InputFilter.LengthFilter(Protocol.MAX_KEYBOARD_CHARS))
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
        }
        val count = TextView(this).apply { text = "0 / ${Protocol.MAX_KEYBOARD_CHARS}" }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, countValue: Int) {
                count.text = "${s?.length ?: 0} / ${Protocol.MAX_KEYBOARD_CHARS}"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        fun sendTyped(key: Int) {
            val text = field.text.toString()
            if (text.isEmpty() && key == Protocol.KEY_NONE) return
            sender.send(text, key)
            field.text.clear()
        }

        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) sendTyped(Protocol.KEY_NONE)
            action == EditorInfo.IME_ACTION_SEND
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(CheckBox(this).apply {
            text = "Show"
            setOnCheckedChangeListener { _, show ->
                field.inputType = if (show) visibleText else hiddenText
                field.setSelection(field.text.length)
            }
        })
        fun addKey(label: String, key: Int) = row.addView(Button(this).apply {
            text = label
            AppUi.styleButton(this)
            setOnClickListener { sendTyped(key) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addKey("Send", Protocol.KEY_NONE)
        addKey("Tab", Protocol.KEY_TAB)
        addKey("Enter", Protocol.KEY_ENTER)
        addKey("Delete", Protocol.KEY_BACKSPACE)
        addKey("Clear", Protocol.KEY_NONE).also { row.getChildAt(row.childCount - 1).setOnClickListener { field.text.clear() } }

        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(field)
            addView(count)
            addView(row)
        }

        keyboardDialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
            .apply {
                window?.setGravity(Gravity.TOP)
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                show()
            }
        field.requestFocus()
    }

    /** Stays open without blocking the screen behind it, so the player can swipe to try each setting. */
    private fun showCameraSensitivityDialog() {
        if (sensitivityDialog?.isShowing == true) return

        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        fun addSlider(
            title: String,
            initial: Int,
            min: Int = AppSettings.MIN_CAMERA_SENSITIVITY,
            upperBound: Int = AppSettings.MAX_CAMERA_SENSITIVITY,
            onChange: (Int) -> Unit,
        ) {
            val label = TextView(this).apply { text = "$title: $initial%" }
            content.addView(label)
            content.addView(SeekBar(this).apply {
                max = upperBound - min
                progress = initial.coerceIn(min, upperBound) - min
                AppUi.styleSlider(this)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        label.text = "$title: ${min + value}%"
                        onChange(min + value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }

        addSlider("Left / right", settings.cameraSensitivityPercent) {
            settings.cameraSensitivityPercent = it
            hud.setCameraSensitivity(it, settings.cameraSensitivityYPercent)
        }
        addSlider("Up / down", settings.cameraSensitivityYPercent) {
            settings.cameraSensitivityYPercent = it
            hud.setCameraSensitivity(settings.cameraSensitivityPercent, it)
        }
        addSlider("Stream display size", settings.displayScalePercent, min = 50, upperBound = 100) {
            settings.displayScalePercent = it
            video.setDisplayScale(it)
        }

        content.addView(Button(this).apply {
            text = "Edit layout over game"
            AppUi.styleButton(this, primary = true)
            setOnClickListener {
                sensitivityDialog?.dismiss()
                showLiveLayoutEditor()
            }
        })

        sensitivityDialog = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("Done", null)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                window?.setGravity(Gravity.TOP)
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                show()
            }
    }

    /** Edit the HUD over the real stream so control placement and the video bounds can be judged together. */
    private fun showLiveLayoutEditor() {
        if (liveLayoutTools != null || isFinishing) return
        hud.setEditMode(true)

        val title = TextView(this).apply {
            text = "Game view size"
            textSize = 13f
            setTextColor(AppUi.textPrimary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val value = TextView(this).apply {
            text = "${settings.displayScalePercent}%"
            textSize = 13f
            setTextColor(AppUi.textSecondary)
            gravity = Gravity.CENTER
            minWidth = dp(48)
        }
        val size = SeekBar(this).apply {
            max = 50
            progress = settings.displayScalePercent - 50
            AppUi.styleSlider(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.displayScalePercent = progress + 50
                    value.text = "${progress + 50}%"
                    video.setDisplayScale(progress + 50)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val fill = Button(this).apply {
            text = if (settings.stretchToFill) "Fill ✓" else "Fill"
            AppUi.styleButton(this, primary = settings.stretchToFill)
            setOnClickListener {
                settings.stretchToFill = !settings.stretchToFill
                video.setStretchToFill(settings.stretchToFill)
                text = if (settings.stretchToFill) "Fill ✓" else "Fill"
                AppUi.styleButton(this, primary = settings.stretchToFill)
            }
        }
        val done = Button(this).apply {
            text = "Done"
            AppUi.styleButton(this, primary = true)
            setOnClickListener {
                settings.layout = layoutModel
                hud.setEditMode(false)
                (liveLayoutTools?.parent as? FrameLayout)?.removeView(liveLayoutTools)
                liveLayoutTools = null
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            AppUi.cardBackground(this)
            addView(title)
            addView(size, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            addView(fill)
            addView(done)
        }
        liveLayoutTools = panel
        streamRoot.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(18)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun onKeyboardResult(result: KeyboardSender.Result) {
        val message = when (result) {
            KeyboardSender.Result.REFUSED -> "Turn on \"Allow mouse and keyboard from phone\" on the PC"
            KeyboardSender.Result.NO_ANSWER -> "The PC didn't get that. Check the field and send it again."
            KeyboardSender.Result.SENT -> return
        }
        runOnUiThread { if (!isFinishing) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun receiveVideo(client: SessionClient) {
        val reassembler = FrameReassembler { data, length, keyframe -> video.submit(data, length, keyframe) }
        val buffer = ByteArray(1500)
        val plain = ByteArray(1500)
        val packet = DatagramPacket(buffer, buffer.size)

        while (!closing) {
            try {
                client.video.receive(packet)
            } catch (_: SocketException) {
                return // socket closed on teardown
            }
            if (packet.address != client.host) continue
            val length = client.keys.video.open(buffer, packet.length, plain)
            if (length >= 0) reassembler.push(plain, length)
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            if (!isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "RemoteGameStreamer").apply { acquire() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && ::hud.isInitialized) hud.setTouchDragCameraEnabled(settings.touchDragCamera)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            physicalGamepad.handleKey(event, event.action == KeyEvent.ACTION_DOWN)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (physicalGamepad.handleMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onStop() {
        super.onStop()
        finish() // no background streaming
    }

    override fun onDestroy() {
        keyboardDialog?.dismiss()
        sensitivityDialog?.dismiss()
        if (::hud.isInitialized) hud.setStreamConnected(false)
        synchronized(this) {
            closing = true
            keyboard?.stop()
            sender?.stop() // sends Bye, so it must run before the sockets close
            audio?.stop()
            video.stop()
            session?.close()
            videoThread?.join(200)
        }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PIN = "pin"
    }
}
