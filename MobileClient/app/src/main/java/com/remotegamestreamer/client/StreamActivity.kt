package com.remotegamestreamer.client

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.remotegamestreamer.client.audio.AudioPlayer
import com.remotegamestreamer.client.decoder.FrameReassembler
import com.remotegamestreamer.client.decoder.VideoRenderSurface
import com.remotegamestreamer.client.input.TouchHudOverlay
import com.remotegamestreamer.client.input.UdpInputSender
import com.remotegamestreamer.client.net.SessionClient
import com.remotegamestreamer.client.net.SessionException
import java.net.DatagramPacket
import java.net.SocketException

/** Full-screen session: video under the translucent HUD. Ends whenever the app leaves the foreground. */
class StreamActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var video: VideoRenderSurface
    private lateinit var hud: TouchHudOverlay

    private var wifiLock: WifiManager.WifiLock? = null
    private var session: SessionClient? = null
    private var sender: UdpInputSender? = null
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
        hud = TouchHudOverlay(this, settings.layout, settings.opacityPercent, settings.stickLocked, settings.cameraSensitivityPercent)

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
            val welcome = client.connect(pin, request)

            synchronized(this) {
                if (closing) {
                    client.close()
                    return
                }
                session = client
                video.configure(welcome.isHevc, welcome.width, welcome.height, welcome.fps)
                runOnUiThread { window.attributes = window.attributes.apply { preferredRefreshRate = welcome.fps.toFloat() } }
                hud.post { hud.touchpadAvailable = welcome.mouseEnabled }

                sender = UdpInputSender(client.control, welcome.token, hud::sample).also { it.start() }
                if (request.sound) audio = AudioPlayer(client.audio, client.host, welcome.sampleRate).also { it.start() }
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

    private fun receiveVideo(client: SessionClient) {
        val reassembler = FrameReassembler { data, length, keyframe -> video.submit(data, length, keyframe) }
        val buffer = ByteArray(1500)
        val packet = DatagramPacket(buffer, buffer.size)

        while (!closing) {
            try {
                client.video.receive(packet)
            } catch (_: SocketException) {
                return // socket closed on teardown
            }
            if (packet.address == client.host) reassembler.push(buffer, packet.length)
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

    override fun onStop() {
        super.onStop()
        finish() // no background streaming
    }

    override fun onDestroy() {
        synchronized(this) {
            closing = true
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
