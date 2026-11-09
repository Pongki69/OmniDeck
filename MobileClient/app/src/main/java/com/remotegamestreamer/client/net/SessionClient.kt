package com.remotegamestreamer.client.net

import com.remotegamestreamer.client.AppSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.interfaces.ECPublicKey

class SessionException(message: String) : Exception(message)

/** Owns the three UDP sockets and performs the encrypted PIN handshake. */
class SessionClient(private val hostAddress: String, port: Int = 9050) {
    val host: InetAddress = InetAddress.getByName(hostAddress)
    val control = DatagramSocket().apply { connect(host, port) }
    val video = DatagramSocket().apply { receiveBufferSize = 4 * 1024 * 1024 }
    val audio = DatagramSocket()

    lateinit var keys: SessionKeys
        private set

    private val buffer = ByteArray(256)
    private val reply = DatagramPacket(buffer, buffer.size)

    /** Blocks until the host answers. Throws [SessionException] with a user-readable reason. */
    fun connect(pin: Int, request: Protocol.StreamRequest, settings: AppSettings): Protocol.Welcome {
        control.soTimeout = 500

        val hostPublic = exchange(Protocol.buildKeyRequest()) { Protocol.parseKeyReply(buffer, it) } ?: throw noAnswer()
        val fingerprint = hostPublic.joinToString("") { "%02X".format(it) }
        val saved = settings.hostKey(hostAddress)
        if (saved != null && saved != fingerprint) {
            throw SessionException("This PC's security key has changed. If you reinstalled OmniDeck, use Settings > Forget saved PCs. If not, someone may be intercepting the connection.")
        }

        val secureHello: ByteArray
        val sessionKeys: SessionKeys
        try {
            val pair = Crypto.newKeyPair()
            val params = (pair.public as ECPublicKey).params
            val clientPublic = Crypto.encodePublic(pair.public)
            val secret = Crypto.sharedSecret(pair.private, Crypto.decodePublic(hostPublic, params))
            sessionKeys = SessionKeys(secret, clientPublic, hostPublic)
            val hello = Protocol.buildHello(pin, video.localPort, if (request.sound) audio.localPort else 0, request)
            secureHello = Protocol.buildSecureHello(clientPublic, sessionKeys.helloKey, hello)
        } catch (_: GeneralSecurityException) {
            throw SessionException("The PC sent an invalid security key.")
        }

        val plain = ByteArray(64)
        val welcome = exchange(secureHello) { length ->
            val n = sessionKeys.control.open(buffer, length, plain)
            if (n < 0) null else Protocol.parseWelcome(plain, n)
        } ?: throw noAnswer()

        when (welcome.status) {
            Protocol.STATUS_OK -> {
                settings.rememberHostKey(hostAddress, fingerprint) // only a PC holding the private key gets this far
                keys = sessionKeys
                return welcome
            }
            Protocol.STATUS_BAD_PIN -> throw SessionException("Wrong PIN")
            Protocol.STATUS_BUSY -> throw SessionException("Another phone is already connected")
            Protocol.STATUS_LOCKED -> throw SessionException("Too many wrong PINs. Wait 30 seconds.")
            else -> throw SessionException("Host refused the connection")
        }
    }

    private fun noAnswer() = SessionException("No answer from the PC. Check the address, that the host is running and up to date, and the Windows firewall.")

    private fun <T> exchange(request: ByteArray, parse: (Int) -> T?): T? {
        repeat(8) {
            try {
                control.send(DatagramPacket(request, request.size))
                control.receive(reply)
            } catch (_: SocketTimeoutException) {
                return@repeat
            } catch (_: java.io.IOException) {
                // ICMP unreachable while the host is not listening yet.
                Thread.sleep(250)
                return@repeat
            }
            parse(reply.length)?.let { return it }
        }
        return null
    }

    fun close() {
        control.close()
        video.close()
        audio.close()
    }
}
