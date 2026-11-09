package com.remotegamestreamer.client.net

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 key exchange and HKDF-SHA256. Mirrors HostServer/Network/SecureTransport.cs. */
object Crypto {
    const val PUBLIC_KEY_SIZE = 65 // 0x04 | X | Y
    private const val COORDINATE_SIZE = 32

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encodePublic(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        val out = ByteArray(PUBLIC_KEY_SIZE)
        out[0] = 4
        writeCoordinate(point.affineX, out, 1)
        writeCoordinate(point.affineY, out, 1 + COORDINATE_SIZE)
        return out
    }

    private fun writeCoordinate(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val length = minOf(bytes.size, COORDINATE_SIZE) // toByteArray may add a sign byte or drop leading zeros
        bytes.copyInto(out, offset + COORDINATE_SIZE - length, bytes.size - length, bytes.size)
    }

    /** Throws [GeneralSecurityException] if [bytes] is not a valid point on the curve. */
    fun decodePublic(bytes: ByteArray, params: ECParameterSpec): PublicKey {
        if (bytes.size != PUBLIC_KEY_SIZE || bytes[0] != 4.toByte()) throw GeneralSecurityException("Bad public key")
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
        if (!isOnCurve(x, y, params)) throw GeneralSecurityException("Public key is not on the curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** Key factories don't all check this themselves, and an off-curve point can leak the private key. */
    private fun isOnCurve(x: BigInteger, y: BigInteger, params: ECParameterSpec): Boolean {
        val p = (params.curve.field as ECFieldFp).p
        if (x >= p || y >= p) return false
        val right = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return y.pow(2).mod(p) == right
    }

    fun sharedSecret(privateKey: PrivateKey, peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(peer, true)
        generateSecret()
    }

    /** HKDF-SHA256 producing one 32-byte key, which needs only a single expand round. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, secret)
        return hmac(prk, info + 1.toByte())
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
}

/**
 * One key in one direction. Packets are 'O' 'D' 0x20 version | counter u64 | ciphertext | 16-byte tag.
 * [seal] may be called from several threads. [open] belongs to one receiving thread at a time.
 */
class StreamCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val sealCipher = Cipher.getInstance(TRANSFORMATION)
    private val openCipher = Cipher.getInstance(TRANSFORMATION)
    private var counter = 0L

    @Synchronized
    fun seal(inner: ByteArray, length: Int = inner.size): ByteArray {
        val out = ByteArray(length + OVERHEAD)
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.put('O'.code.toByte()).put('D'.code.toByte()).put(Protocol.TYPE_SEALED).put(1).putLong(++counter)

        val nonce = ByteArray(NONCE_SIZE)
        out.copyInto(nonce, 4, 4, Protocol.SEALED_HEADER_SIZE)
        sealCipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        sealCipher.updateAAD(out, 0, Protocol.SEALED_HEADER_SIZE)
        sealCipher.doFinal(inner, 0, length, out, Protocol.SEALED_HEADER_SIZE)
        return out
    }

    /** Decrypts into [out] and returns the length, or -1 if the packet is malformed or was tampered with. */
    fun open(d: ByteArray, length: Int, out: ByteArray): Int {
        if (length < OVERHEAD || !Protocol.hasHeader(d, length, Protocol.TYPE_SEALED)) return -1

        val nonce = ByteArray(NONCE_SIZE)
        d.copyInto(nonce, 4, 4, Protocol.SEALED_HEADER_SIZE)
        return try {
            openCipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            openCipher.updateAAD(d, 0, Protocol.SEALED_HEADER_SIZE)
            openCipher.doFinal(d, Protocol.SEALED_HEADER_SIZE, length - Protocol.SEALED_HEADER_SIZE, out, 0)
        } catch (_: GeneralSecurityException) {
            -1
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_SIZE = 12
        const val OVERHEAD = Protocol.SEALED_HEADER_SIZE + TAG_BITS / 8
    }
}

/** Per-connection keys, all derived from one ECDH secret. */
class SessionKeys(sharedSecret: ByteArray, clientPublic: ByteArray, hostPublic: ByteArray) {
    private val salt = clientPublic + hostPublic

    private fun derive(sharedSecret: ByteArray, label: String) =
        Crypto.hkdf(sharedSecret, salt, "OmniDeck $label".toByteArray(Charsets.US_ASCII))

    val helloKey = derive(sharedSecret, "hello")
    val clientToHost = StreamCipher(derive(sharedSecret, "c2s"))
    val control = StreamCipher(derive(sharedSecret, "control"))
    val video = StreamCipher(derive(sharedSecret, "video"))
    val audio = StreamCipher(derive(sharedSecret, "audio"))
}
