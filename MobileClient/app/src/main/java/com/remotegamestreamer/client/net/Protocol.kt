package com.remotegamestreamer.client.net

import com.remotegamestreamer.client.input.ControllerState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire format shared with HostServer/Network/Protocol.cs. All integers little-endian. */
object Protocol {
    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_WELCOME: Byte = 0x02
    const val TYPE_INPUT: Byte = 0x03
    const val TYPE_BYE: Byte = 0x04
    const val TYPE_KEYBOARD: Byte = 0x05
    const val TYPE_KEYBOARD_ACK: Byte = 0x06
    const val TYPE_KEY_REQUEST: Byte = 0x07
    const val TYPE_KEY_REPLY: Byte = 0x08
    const val TYPE_SECURE_HELLO: Byte = 0x09
    const val TYPE_VIDEO: Byte = 0x10
    const val TYPE_AUDIO: Byte = 0x11
    const val TYPE_SEALED: Byte = 0x20

    const val HELLO_SIZE = 20
    const val WELCOME_SIZE = 20
    const val INPUT_SIZE = 12 + ControllerState.SIZE
    const val BYE_SIZE = 8
    const val KEYBOARD_HEADER_SIZE = 14
    const val MAX_KEYBOARD_CHARS = 256
    const val KEYBOARD_ACK_SIZE = 9
    const val KEY_REQUEST_SIZE = 69
    const val KEY_REPLY_SIZE = 69
    const val SEALED_HEADER_SIZE = 12
    private const val HEADER_SIZE = 4

    const val KEY_NONE = 0
    const val KEY_BACKSPACE = 1
    const val KEY_TAB = 2
    const val KEY_ENTER = 3

    const val STATUS_OK = 0
    const val STATUS_BAD_PIN = 1
    const val STATUS_BUSY = 2
    const val STATUS_LOCKED = 3

    fun hasHeader(d: ByteArray, length: Int, type: Byte): Boolean =
        length >= 4 && d[0] == 'O'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == type && d[3] == 1.toByte()

    private fun ByteBuffer.putHeader(type: Byte) {
        put('O'.code.toByte())
        put('D'.code.toByte())
        put(type)
        put(1)
    }

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** What the phone asks the host to stream. */
    data class StreamRequest(val maxHeight: Int, val fps: Int, val bitrateMbps: Int, val hevc: Boolean, val sound: Boolean)

    private const val CODEC_H264: Byte = 1
    private const val CODEC_HEVC: Byte = 2

    /** [audioPort] 0 tells the host not to send sound. */
    fun buildHello(pin: Int, videoPort: Int, audioPort: Int, request: StreamRequest): ByteArray =
        buffer(HELLO_SIZE).apply {
            putHeader(TYPE_HELLO)
            putInt(pin)
            putShort(videoPort.toShort())
            putShort(audioPort.toShort())
            putShort(request.maxHeight.toShort())
            put(request.fps.toByte())
            put(request.bitrateMbps.toByte())
            put(if (request.hevc) CODEC_HEVC else CODEC_H264)
        }.array()

    fun buildKeyRequest(): ByteArray = buffer(KEY_REQUEST_SIZE).apply { putHeader(TYPE_KEY_REQUEST) }.array()

    fun parseKeyReply(d: ByteArray, length: Int): ByteArray? =
        if (length == KEY_REPLY_SIZE && hasHeader(d, length, TYPE_KEY_REPLY)) d.copyOfRange(HEADER_SIZE, KEY_REPLY_SIZE) else null

    /** Encrypts [hello], and with it the PIN, to the key shared with the PC. */
    fun buildSecureHello(clientPublic: ByteArray, helloKey: ByteArray, hello: ByteArray): ByteArray {
        val prefix = buffer(HEADER_SIZE + clientPublic.size).apply {
            putHeader(TYPE_SECURE_HELLO)
            put(clientPublic)
        }.array()
        val cipher = Cipher.getInstance(StreamCipher.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(helloKey, "AES"), GCMParameterSpec(StreamCipher.TAG_BITS, ByteArray(StreamCipher.NONCE_SIZE)))
        cipher.updateAAD(prefix)
        return prefix + cipher.doFinal(hello)
    }

    fun buildBye(token: Int): ByteArray =
        buffer(BYE_SIZE).apply {
            putHeader(TYPE_BYE)
            putInt(token)
        }.array()

    /** [text] is typed first, then [key] is pressed. */
    fun buildKeyboard(token: Int, id: Int, text: String, key: Int): ByteArray {
        require(text.length <= MAX_KEYBOARD_CHARS)
        return buffer(KEYBOARD_HEADER_SIZE + text.length * 2).apply {
            putHeader(TYPE_KEYBOARD)
            putInt(token)
            putInt(id)
            put(key.toByte())
            put(0)
            for (c in text) putShort(c.code.toShort())
        }.array()
    }

    data class KeyboardAck(val id: Int, val accepted: Boolean)

    fun parseKeyboardAck(d: ByteArray, length: Int): KeyboardAck? {
        if (length != KEYBOARD_ACK_SIZE || !hasHeader(d, length, TYPE_KEYBOARD_ACK)) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return KeyboardAck(b.getInt(4), b.get(8).toInt() == 1)
    }

    /** Writes a full input datagram into [out], which must be [INPUT_SIZE] bytes. */
    fun writeInput(out: ByteArray, token: Int, seq: Int, s: ControllerState) {
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            putHeader(TYPE_INPUT)
            putInt(token)
            putInt(seq)
            putShort(s.buttons.toShort())
            put(s.leftTrigger.toByte())
            put(s.rightTrigger.toByte())
            putShort(s.thumbLX)
            putShort(s.thumbLY)
            putShort(s.thumbRX)
            putShort(s.thumbRY)
            putShort(s.mouseDeltaX)
            putShort(s.mouseDeltaY)
            put(s.mouseButtons.toByte())
        }
    }

    data class Welcome(
        val status: Int,
        val isHevc: Boolean,
        val fps: Int,
        val token: Int,
        val width: Int,
        val height: Int,
        val sampleRate: Int,
        val mouseEnabled: Boolean,
    )

    fun parseWelcome(d: ByteArray, length: Int): Welcome? {
        if (length != WELCOME_SIZE || !hasHeader(d, length, TYPE_WELCOME)) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return Welcome(
            status = b.get(4).toInt() and 0xFF,
            isHevc = b.get(5).toInt() == 1,
            fps = b.get(6).toInt() and 0xFF,
            token = b.getInt(8),
            width = b.getShort(12).toInt() and 0xFFFF,
            height = b.getShort(14).toInt() and 0xFFFF,
            sampleRate = b.getInt(16),
            mouseEnabled = b.get(7).toInt() == 1,
        )
    }
}
