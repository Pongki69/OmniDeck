package com.remotegamestreamer.client.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Hardware H.264/HEVC decoder rendering straight to a SurfaceView, letterboxed to the stream. */
class VideoRenderSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var onDecoderError: ((String) -> Unit)? = null

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var surfaceReady = false

    private var mime: String? = null
    private var streamWidth = 0
    private var streamHeight = 0
    private var streamFps = 60
    private var presentationUs = 0L

    init {
        holder.addCallback(this)
    }

    /** Safe to call from any thread, before or after the surface exists. */
    fun configure(isHevc: Boolean, width: Int, height: Int, fps: Int) {
        synchronized(lock) {
            mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            streamWidth = width
            streamHeight = height
            streamFps = fps
            startLocked()
        }
        post { requestLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableW = MeasureSpec.getSize(widthMeasureSpec)
        val availableH = MeasureSpec.getSize(heightMeasureSpec)
        if (streamWidth == 0 || streamHeight == 0 || availableW == 0 || availableH == 0) {
            setMeasuredDimension(availableW, availableH)
            return
        }
        val fitHeight = availableW * streamHeight / streamWidth
        if (fitHeight <= availableH) setMeasuredDimension(availableW, fitHeight)
        else setMeasuredDimension(availableH * streamWidth / streamHeight, availableH)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) {
            surfaceReady = true
            startLocked()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) {
            surfaceReady = false
            stopLocked()
        }
    }

    private fun startLocked() {
        val type = mime ?: return
        if (!surfaceReady || codec != null) return

        try {
            val format = MediaFormat.createVideoFormat(type, streamWidth, streamHeight).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, streamWidth * streamHeight)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1) // ignored on non-Qualcomm decoders
            }
            // Asks the phone to run its display at the stream's rate (for example 120 Hz).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                holder.surface.setFrameRate(streamFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            val decoder = MediaCodec.createDecoderByType(type)
            decoder.configure(format, holder.surface, null, 0)
            decoder.start()
            codec = decoder
            outputThread = Thread({ outputLoop(decoder) }, "VideoOutput").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            onDecoderError?.invoke("This phone could not start the video decoder: ${e.message}")
        }
    }

    /** Feeds one complete access unit. Called from the receive thread. */
    fun submit(data: ByteArray, length: Int, keyframe: Boolean) {
        synchronized(lock) {
            val decoder = codec ?: return
            try {
                val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) return // decoder busy: drop rather than build up latency
                val buffer = decoder.getInputBuffer(index) ?: return
                if (length > buffer.capacity()) {
                    decoder.queueInputBuffer(index, 0, 0, presentationUs, 0)
                    return
                }
                buffer.clear()
                buffer.put(data, 0, length)
                presentationUs += 16_666
                decoder.queueInputBuffer(index, 0, length, presentationUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (_: IllegalStateException) {
                // Decoder was stopped underneath us during teardown.
            }
        }
    }

    private fun outputLoop(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val index = decoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) decoder.releaseOutputBuffer(index, true) // render immediately
            }
        } catch (_: IllegalStateException) {
            // Normal path when the codec is stopped.
        }
    }

    fun stop() {
        synchronized(lock) {
            mime = null
            stopLocked()
        }
    }

    private fun stopLocked() {
        val decoder = codec ?: return
        codec = null
        outputThread?.interrupt()
        outputThread = null
        try {
            decoder.stop()
        } catch (_: IllegalStateException) {
            // Already in an error state: release below still frees it.
        }
        decoder.release()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 4_000L
        const val OUTPUT_TIMEOUT_US = 8_000L
    }
}
