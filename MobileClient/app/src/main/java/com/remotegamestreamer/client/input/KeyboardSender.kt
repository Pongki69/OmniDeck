package com.remotegamestreamer.client.input

import com.remotegamestreamer.client.net.Protocol
import com.remotegamestreamer.client.net.SessionKeys
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sends typed text to the PC one message at a time, resending until acknowledged, because a
 * lost UDP packet would otherwise silently drop part of a password. The only reader of the
 * control socket once the session has started.
 */
class KeyboardSender(
    private val socket: DatagramSocket,
    private val token: Int,
    private val keys: SessionKeys,
    private val onResult: (Result) -> Unit,
) {
    enum class Result { SENT, REFUSED, NO_ANSWER }

    private class Job(val text: String, val key: Int)

    private val queue = LinkedBlockingQueue<Job>()
    @Volatile private var running = true
    private val thread = Thread(::loop, "KeyboardSender").apply {
        isDaemon = true
        start()
    }

    fun send(text: String, key: Int) {
        queue.add(Job(text, key))
    }

    private fun loop() {
        var id = 0
        try {
            while (running) {
                val job = queue.take()
                val result = deliver(++id, job)
                if (running && result != Result.SENT) onResult(result)
                if (result != Result.SENT) queue.clear() // never type the rest of something that partly failed
            }
        } catch (_: InterruptedException) {
            // Session ended.
        } catch (_: IOException) {
            // Socket closed on teardown.
        }
    }

    private fun deliver(id: Int, job: Job): Result {
        val bytes = keys.clientToHost.seal(Protocol.buildKeyboard(token, id, job.text, job.key))
        val buffer = ByteArray(128)
        val plain = ByteArray(64)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.soTimeout = RECEIVE_TIMEOUT_MS

        repeat(MAX_ATTEMPTS) {
            if (!running) return Result.NO_ANSWER
            socket.send(DatagramPacket(bytes, bytes.size))
            try {
                while (true) {
                    socket.receive(reply)
                    val n = keys.control.open(buffer, reply.length, plain)
                    val ack = (if (n < 0) null else Protocol.parseKeyboardAck(plain, n)) ?: continue
                    if (ack.id == id) return if (ack.accepted) Result.SENT else Result.REFUSED
                }
            } catch (_: SocketTimeoutException) {
                // Lost on the way there or back. Send it again.
            }
        }
        return Result.NO_ANSWER
    }

    fun stop() {
        running = false
        queue.clear()
        thread.interrupt()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 150
        const val MAX_ATTEMPTS = 14
    }
}
