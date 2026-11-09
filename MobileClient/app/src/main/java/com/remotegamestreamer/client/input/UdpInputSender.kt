package com.remotegamestreamer.client.input

import com.remotegamestreamer.client.net.Protocol
import com.remotegamestreamer.client.net.StreamCipher
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.locks.LockSupport

/** Emits the current controller state every 5 ms (200 Hz) from one dedicated thread. */
class UdpInputSender(
    private val socket: DatagramSocket,
    private val token: Int,
    private val cipher: StreamCipher,
    private val sample: (ControllerState) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "UdpInputSender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        val state = ControllerState()
        val bytes = ByteArray(Protocol.INPUT_SIZE)
        var seq = 0
        var next = System.nanoTime()

        while (running) {
            sample(state)
            Protocol.writeInput(bytes, token, ++seq, state)
            try {
                val sealed = cipher.seal(bytes)
                socket.send(DatagramPacket(sealed, sealed.size))
            } catch (_: IOException) {
                // Transient Wi-Fi loss. The host's watchdog releases held inputs if this persists.
            }

            next += PERIOD_NANOS
            val wait = next - System.nanoTime()
            if (wait > 0) {
                LockSupport.parkNanos(wait)
            } else if (wait < -MAX_LAG_NANOS) {
                next = System.nanoTime() // don't burst to catch up after a stall
            }
        }

        try {
            val bye = cipher.seal(Protocol.buildBye(token))
            socket.send(DatagramPacket(bye, bye.size))
        } catch (_: IOException) {
            // Host will time the session out instead.
        }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }

    private companion object {
        const val PERIOD_NANOS = 5_000_000L
        const val MAX_LAG_NANOS = 50_000_000L
    }
}
