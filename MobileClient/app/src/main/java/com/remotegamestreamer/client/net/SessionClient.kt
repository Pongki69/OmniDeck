package com.remotegamestreamer.client.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class SessionException(message: String) : Exception(message)

/** Owns the three UDP sockets and performs the PIN handshake. */
class SessionClient(hostAddress: String, port: Int = 9050) {
    val host: InetAddress = InetAddress.getByName(hostAddress)
    val control = DatagramSocket().apply { connect(host, port) }
    val video = DatagramSocket().apply { receiveBufferSize = 4 * 1024 * 1024 }
    val audio = DatagramSocket()

    /** Blocks until the host answers. Throws [SessionException] with a user-readable reason. */
    fun connect(pin: Int, request: Protocol.StreamRequest): Protocol.Welcome {
        val hello = Protocol.buildHello(pin, video.localPort, if (request.sound) audio.localPort else 0, request)
        val buffer = ByteArray(64)
        val reply = DatagramPacket(buffer, buffer.size)
        control.soTimeout = 500

        repeat(8) {
            try {
                control.send(DatagramPacket(hello, hello.size))
                control.receive(reply)
            } catch (_: SocketTimeoutException) {
                return@repeat
            } catch (_: java.io.IOException) {
                // ICMP unreachable while the host is not listening yet.
                Thread.sleep(250)
                return@repeat
            }
            val welcome = Protocol.parseWelcome(buffer, reply.length) ?: return@repeat
            when (welcome.status) {
                Protocol.STATUS_OK -> return welcome
                Protocol.STATUS_BAD_PIN -> throw SessionException("Wrong PIN")
                Protocol.STATUS_BUSY -> throw SessionException("Another phone is already connected")
                Protocol.STATUS_LOCKED -> throw SessionException("Too many wrong PINs. Wait 30 seconds.")
                else -> throw SessionException("Host refused the connection")
            }
        }
        throw SessionException("No answer from the PC. Check the address, that the host is running, and the Windows firewall.")
    }

    fun close() {
        control.close()
        video.close()
        audio.close()
    }
}
