package com.remotegamestreamer.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.remotegamestreamer.client.net.Protocol
import com.remotegamestreamer.client.net.StreamCipher
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plays the host's raw stereo PCM with the smallest buffer the device allows. */
class AudioPlayer(
    private val socket: DatagramSocket,
    private val host: InetAddress,
    sampleRate: Int,
    private val cipher: StreamCipher,
) {
    private val track: AudioTrack
    private var thread: Thread? = null
    @Volatile private var running = false

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun start() {
        running = true
        track.play()
        thread = Thread(::loop, "AudioReceive").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        val plain = ByteArray(2048)
        val header = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        var lastSeq = 0L

        while (running) {
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return // socket closed on teardown
            }
            if (packet.address != host) continue
            val length = cipher.open(buffer, packet.length, plain)
            if (length <= 8 || !Protocol.hasHeader(plain, length, Protocol.TYPE_AUDIO)) continue

            val seq = header.getInt(4).toLong() and 0xFFFFFFFFL
            if (seq <= lastSeq) continue
            lastSeq = seq

            // Non-blocking: when the buffer is full, the excess is dropped so delay cannot grow.
            track.write(plain, 8, length - 8, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    fun stop() {
        running = false
        socket.close() // unblocks receive(), so the thread can't write to a released track
        thread?.join(200)
        thread = null
        track.pause()
        track.flush()
        track.release()
    }
}
