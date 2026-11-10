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
        hud = TouchHudOverlay(this, settings.layout, settings.opacityPercent, settings.stickLocked, settings.cameraSensitivityPercent, settings.cameraSensitivityYPercent)
        hud.onKeyboardRequested = ::showKeyboardDialog
        hud.onCameraSensitivityRequested = ::showCameraSensitivityDialog

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            },
        )
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
                hud.post { hud.touchpadAvailable = welcome.mouseEnabled }

                sender = UdpInputSender(client.control, welcome.token, client.keys.clientToHost) { state ->
                    hud.sample(state)
                    physicalGamepad.merge(state)
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
            onChange: (Int) -> Unit,
            min: Int = AppSettings.MIN_CAMERA_SENSITIVITY,
            upperBound: Int = AppSettings.MAX_CAMERA_SENSITIVITY,
        ) {
            val label = TextView(this).apply { text = "$title: $initial%" }
            content.addView(label)
            content.addView(SeekBar(this).apply {
                max = upperBound - min
                progress = initial.coerceIn(min, upperBound) - min
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
        addSlider("Stream display size", settings.displayScalePercent, {
            settings.displayScalePercent = it
            video.setDisplayScale(it)
        }, min = 50, upperBound = 100)

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
