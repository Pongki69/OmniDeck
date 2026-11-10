package com.remotegamestreamer.client

import com.remotegamestreamer.client.decoder.FrameReassembler
import com.remotegamestreamer.client.input.ControlKind
import com.remotegamestreamer.client.input.ControllerState
import com.remotegamestreamer.client.input.HudCatalog
import com.remotegamestreamer.client.input.CameraMotion
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.input.InputNormalizer
import com.remotegamestreamer.client.net.Crypto
import com.remotegamestreamer.client.net.Protocol
import com.remotegamestreamer.client.net.SessionKeys
import com.remotegamestreamer.client.net.StreamCipher
import com.remotegamestreamer.client.update.UpdateRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

class ProtocolTest {
    /** Same bytes are asserted by HostTests.cs on the PC side. */
    private val goldenInput = "4F44030178563412020000000102FF803412CDAB00801027F0D800FE03"

    @Test
    fun inputDatagramMatchesHostLayout() {
        val s = ControllerState().apply {
            buttons = 0x0201
            leftTrigger = 255
            rightTrigger = 128
            thumbLX = 0x1234
            thumbLY = 0xABCD.toShort()
            thumbRX = Short.MIN_VALUE
            thumbRY = 10000
            mouseDeltaX = -10000
            mouseDeltaY = -512
            mouseButtons = 3
        }
        val out = ByteArray(Protocol.INPUT_SIZE)
        Protocol.writeInput(out, 0x12345678, 2, s)
        assertEquals(goldenInput, out.toHex())
    }

    @Test
    fun helloMatchesHostLayout() {
        val request = Protocol.StreamRequest(maxHeight = 1080, fps = 60, bitrateMbps = 30, hevc = true, sound = true)
        assertEquals("4F44010140E2010028A029A038043C1E02000000", Protocol.buildHello(123456, 41000, 41001, request).toHex())
    }

    @Test
    fun keyboardMatchesHostLayout() {
        assertEquals(
            "4F440501785634120700000003004100E900",
            Protocol.buildKeyboard(0x12345678, 7, "Aé", Protocol.KEY_ENTER).toHex(),
        )
    }

    @Test
    fun keyboardAckParses() {
        val ack = Protocol.parseKeyboardAck(hex("4F4406010700000001"), 9)!!
        assertEquals(7, ack.id)
        assertTrue(ack.accepted)
        assertEquals(null, Protocol.parseKeyboardAck(hex("4F4402010700000001"), 9))
    }

    @Test
    fun welcomeParses() {
        val w = Protocol.parseWelcome(hex("4F440201" + "00003C00" + "78563412" + "8007" + "3804" + "80BB0000"), 20)!!
        assertEquals(Protocol.STATUS_OK, w.status)
        assertEquals(0x12345678, w.token)
        assertEquals(1920, w.width)
        assertEquals(1080, w.height)
        assertEquals(48000, w.sampleRate)
        assertFalse(w.mouseEnabled)
    }

    @Test
    fun welcomeReportsMouseEnabled() {
        val w = Protocol.parseWelcome(hex("4F440201" + "00003C01" + "78563412" + "8007" + "3804" + "80BB0000"), 20)!!
        assertTrue(w.mouseEnabled)
    }
}

class UpdateRulesTest {
    @Test
    fun onlyHigherVersionsCount() {
        assertTrue(UpdateRules.isNewer("v1.2.3", "1.2.2"))
        assertTrue(UpdateRules.isNewer("v1.10.0", "1.9.9"))
        assertTrue(UpdateRules.isNewer("v2.0.0", "1.99.99"))
        assertFalse(UpdateRules.isNewer("v1.2.3", "1.2.3"))
        assertFalse(UpdateRules.isNewer("v1.2.2", "1.2.3"))
    }

    @Test
    fun malformedVersionsNeverCount() {
        assertFalse(UpdateRules.isNewer("latest", "1.0.0"))
        assertFalse(UpdateRules.isNewer("v2.0", "1.0.0"))
        assertFalse(UpdateRules.isNewer("v2.0.0", ""))
    }

    @Test
    fun onlyThisProjectsReleasesAreTrusted() {
        assertTrue(UpdateRules.isTrustedDownload("https://github.com/Pongki69/OmniDeck/releases/download/v1.0.0/OmniDeck.apk"))
        assertFalse(UpdateRules.isTrustedDownload("https://example.com/OmniDeck.apk"))
        assertFalse(UpdateRules.isTrustedDownload("http://github.com/Pongki69/OmniDeck/releases/download/v1.0.0/OmniDeck.apk"))
    }
}

class CryptoTest {
    // Generated independently with Node's crypto module. The same values are asserted by HostTests.cs.
    private val clientPublic = hex("040217E617F0B6443928278F96999E69A23A4F2C152BDF6D6CDF66E5B80282D4ED194A7DEBCB97712D2DDA3CA85AA8765A56F45FC758599652F2897C65306E5794")
    private val hostPublic = hex("04D65A93977CAA3D1B081852FF57A79E465F1660577304BAEAD505DD3A48589CF350185E895372DF6221EA3A137557E473FDDB6755F05BD507C3C533FCE9C91285")
    private val goldenBye = hex("4F44200101000000000000007C501143F62F97E45E6D40442FA0340A68A2DFDE8D21510A")
    private val hello = hex("4F44010140E2010028A029A038043C1E02000000")

    private val params = (Crypto.newKeyPair().public as ECPublicKey).params

    private fun keys(): SessionKeys {
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger("11".repeat(32), 16), params))
        return SessionKeys(Crypto.sharedSecret(privateKey, Crypto.decodePublic(hostPublic, params)), clientPublic, hostPublic)
    }

    private fun label(l: String) = "OmniDeck $l".toByteArray()

    @Test
    fun secureHelloMatchesTheOtherImplementations() {
        assertEquals(
            "4F440901" + clientPublic.toHex() + "93C932B6D50F8C6C0EC2981ECBA93CAEFAED192B466707D1CC174731D1EC65846BDBC60C",
            Protocol.buildSecureHello(clientPublic, keys().helloKey, hello).toHex(),
        )
    }

    @Test
    fun sealMatchesTheOtherImplementations() {
        assertEquals(goldenBye.toHex(), keys().clientToHost.seal(hex("4F44040178563412")).toHex())
    }

    @Test
    fun derivedKeysMatchTheOtherImplementations() {
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger("11".repeat(32), 16), params))
        val secret = Crypto.sharedSecret(privateKey, Crypto.decodePublic(hostPublic, params))
        val salt = clientPublic + hostPublic
        assertEquals("2E4FD57A1361FA02F748AA4B6F2F33EA0B16B410DBF08B2A7E54E8AC421DB569", Crypto.hkdf(secret, salt, label("video")).toHex())
        assertEquals("64B1858B09A431397EDF739DBD213DCB115D485637087A164C88240B5948A4C4", Crypto.hkdf(secret, salt, label("audio")).toHex())
    }

    @Test
    fun publicKeysRoundTrip() {
        val pair = Crypto.newKeyPair()
        val encoded = Crypto.encodePublic(pair.public)
        assertEquals(Crypto.PUBLIC_KEY_SIZE, encoded.size)
        assertArrayEquals(encoded, Crypto.encodePublic(Crypto.decodePublic(encoded, params)))
    }

    @Test(expected = GeneralSecurityException::class)
    fun pointsOffTheCurveAreRejected() {
        val bad = hostPublic.copyOf()
        bad[64] = (bad[64] + 1).toByte()
        Crypto.decodePublic(bad, params)
    }

    @Test
    fun sealedPacketsOpenAndEachUsesANewCounter() {
        val cipher = StreamCipher(ByteArray(32) { 7 })
        val first = cipher.seal(byteArrayOf(1, 2, 3))
        val second = cipher.seal(byteArrayOf(1, 2, 3))
        assertFalse(first.contentEquals(second))

        val out = ByteArray(16)
        assertEquals(3, cipher.open(first, first.size, out))
        assertArrayEquals(byteArrayOf(1, 2, 3), out.copyOf(3))
    }

    @Test
    fun tamperingAndWrongKeysAreRejected() {
        val cipher = StreamCipher(ByteArray(32) { 7 })
        val packet = cipher.seal(byteArrayOf(1, 2, 3))
        val out = ByteArray(16)

        assertEquals(-1, StreamCipher(ByteArray(32) { 8 }).open(packet, packet.size, out))

        val tampered = packet.copyOf().also { it[13] = (it[13] + 1).toByte() }
        assertEquals(-1, cipher.open(tampered, tampered.size, out))

        val wrongCounter = packet.copyOf().also { it[4] = 9 }
        assertEquals(-1, cipher.open(wrongCounter, wrongCounter.size, out))
    }
}

class FrameReassemblerTest {
    // Frame bytes 01..0A, payload 4, id 7, keyframe. Same shards asserted by HostTests.cs.
    private val s0 = hex("4F441001070000000A00000000000300010001020304")
    private val s1 = hex("4F441001070000000A00000001000300010005060708")
    private val s2 = hex("4F441001070000000A000000020003000100090A")
    private val parity = hex("4F441001070000000A0000000300030003000D0E040C")
    private val expected = ByteArray(10) { (it + 1).toByte() }

    private fun run(vararg shards: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val r = FrameReassembler(4) { d, n, _ -> out.add(d.copyOf(n)) }
        shards.forEach { r.push(it, it.size) }
        return out
    }

    @Test fun allShards() = assertArrayEquals(expected, run(s0, s1, s2, parity).single())
    @Test fun repairsLostFirst() = assertArrayEquals(expected, run(s1, s2, parity).single())
    @Test fun repairsLostMiddle() = assertArrayEquals(expected, run(parity, s0, s2).single())
    @Test fun repairsLostShortLast() = assertArrayEquals(expected, run(s0, s1, parity).single())
    @Test fun twoLossesCannotComplete() = assertTrue(run(s0, parity).isEmpty())
    @Test fun duplicatesDeliverOnce() = assertEquals(1, run(s0, s0, s1, s1, s2, s2, parity).size)

    private fun frame(id: Int, key: Boolean, vararg body: Byte): ByteArray {
        val h = ByteArray(FrameReassembler.HEADER_SIZE + body.size)
        "OD".toByteArray().copyInto(h)
        h[2] = Protocol.TYPE_VIDEO
        h[3] = 1
        h[4] = id.toByte()
        h[8] = body.size.toByte()
        h[14] = 1
        h[16] = if (key) 1 else 0
        body.copyInto(h, FrameReassembler.HEADER_SIZE)
        return h
    }

    @Test
    fun nothingIsDeliveredBeforeTheFirstKeyframe() {
        val got = run(frame(1, false, 1), frame(2, true, 2), frame(3, false, 3))
        assertEquals(listOf(2.toByte(), 3.toByte()), got.map { it[0] })
    }

    @Test
    fun lateFrameAfterNewerOneIsDropped() {
        val got = run(frame(1, true, 1), frame(3, false, 3), frame(2, false, 2))
        assertEquals(listOf(1.toByte(), 3.toByte()), got.map { it[0] })
    }

    @Test
    fun malformedDatagramsAreIgnored() {
        assertTrue(run(ByteArray(3), ByteArray(40), hex("4F441001070000000A000000090003000100")).isEmpty())
    }
}

class HudLayoutTest {
    @Test
    fun catalogIdsAreUniqueAndSafeToStore() {
        val ids = HudCatalog.controls.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.contains(',') || it.contains(';') })
    }

    @Test
    fun everyXboxInputExceptGuideHasAControl() {
        val bits = HudCatalog.controls.fold(0) { acc, c -> acc or c.bit }
        assertEquals((1 shl 14) - 1, bits)
        assertTrue(HudCatalog.controls.any { it.kind == ControlKind.LEFT_TRIGGER })
        assertTrue(HudCatalog.controls.any { it.kind == ControlKind.RIGHT_TRIGGER })
    }

    @Test
    fun layoutSurvivesSaveAndLoad() {
        val a = HudLayout()
        a.move("a", 0.25f, 0.75f)
        a.resize("a", 0.5f)
        a.toggleVisible("l3")

        val b = HudLayout.parse(a.serialize())
        assertEquals(0.25f, b.placement("a").fx, 0.0001f)
        assertEquals(0.75f, b.placement("a").fy, 0.0001f)
        assertEquals(1.5f, b.placement("a").scale, 0.0001f)
        assertFalse(b.placement("l3").visible)
        assertTrue(b.placement("b").visible)
    }

    @Test
    fun positionsAndSizesStayInBounds() {
        val l = HudLayout()
        l.move("a", -3f, 9f)
        l.resize("a", 50f)
        assertEquals(0f, l.placement("a").fx, 0f)
        assertEquals(1f, l.placement("a").fy, 0f)
        assertEquals(HudLayout.MAX_SCALE, l.placement("a").scale, 0f)
    }

    @Test
    fun stickCannotBeHiddenEvenFromSavedData() {
        val l = HudLayout()
        l.toggleVisible("stick")
        assertTrue(l.placement("stick").visible)
        assertTrue(HudLayout.parse("stick,0.5,0.5,1.0,0").placement("stick").visible)
    }

    @Test
    fun damagedDataFallsBackToDefaults() {
        val l = HudLayout.parse("nonsense;a,NaN,1,1,1;b,x,y,z,1;unknown,0.1,0.1,1,1;;")
        assertEquals(0.88f, l.placement("a").fx, 0f)
        assertEquals(0.95f, l.placement("b").fx, 0f)
        assertEquals(1f, HudLayout.parse(null).placement("a").scale, 0f)
    }

    @Test
    fun resetRestoresDefaults() {
        val l = HudLayout()
        l.move("y", 0.1f, 0.1f)
        l.toggleVisible("y")
        l.reset()
        assertEquals(0.88f, l.placement("y").fx, 0f)
        assertTrue(l.placement("y").visible)
    }
}

class CameraMotionTest {
    private val out = ShortArray(2)

    /** Runs until the stick returns to center and returns total travel in ms of full stick. */
    private fun CameraMotion.drain(): Float {
        var travel = 0f
        repeat(2000) {
            step(5f, out)
            travel += out[0] / 32767f * 5f
        }
        return travel
    }

    private fun swipe(gain: Float, dp: Float): Float = CameraMotion().run {
        add(dp, 0f, gain)
        drain()
    }

    @Test
    fun higherSensitivityGivesMoreTravelForAFastSwipe() {
        val at100 = swipe(0.75f, 200f)
        val at400 = swipe(3f, 200f)
        assertEquals(150f, at100, 8f)
        assertEquals(400f, at400, 8f)
    }

    @Test
    fun stickNeverLeavesItsRangeAndKeepsItsDirection() {
        val motion = CameraMotion()
        motion.add(3000f, 4000f, 3f)
        motion.step(5f, out)
        assertEquals(0.6f, out[0] / 32767f, 0.01f)
        assertEquals(0.8f, out[1] / 32767f, 0.01f)
    }

    @Test
    fun coastingAfterTheFingerStopsIsLimited() {
        val motion = CameraMotion(maxPending = 400f)
        motion.add(100000f, 0f, 3f)
        repeat(110) { motion.step(5f, out) } // 400 ms at full stick, then a short ease back to center
        assertEquals(0.toShort(), out[0])
    }

    @Test
    fun reversingCancelsWhatIsStillPending() {
        val motion = CameraMotion()
        motion.add(100f, 0f, 3f)
        motion.add(-100f, 0f, 3f)
        motion.step(5f, out)
        assertEquals(0.toShort(), out[0])
    }

    @Test
    fun aLongStallCannotOvershootBackwards() {
        val motion = CameraMotion()
        motion.add(1f, 0f, 1f)
        motion.step(5000f, out)
        motion.step(5f, out)
        assertEquals(0.toShort(), out[0])
    }

    @Test
    fun aSmallSwipeStaysSmoothAndStillArrives() {
        val motion = CameraMotion()
        motion.add(10f, 0f, 0.75f) // 7.5 ms of full stick
        motion.step(5f, out)
        assertTrue(out[0] / 32767f < 0.7f)
        assertEquals(7.5f - out[0] / 32767f * 5f, motion.drain(), 0.6f)
    }

    @Test
    fun horizontalAndVerticalSensitivityAreIndependent() {
        val motion = CameraMotion()
        motion.add(1f, 1f, gainX = 2f, gainY = 0.5f)
        motion.step(5f, out)
        assertEquals(4f, out[0].toFloat() / out[1], 0.01f)
    }
}

class InputNormalizerTest {
    @Test
    fun axisCoversFullRange() {
        assertEquals(32767.toShort(), InputNormalizer.axis(1f))
        assertEquals((-32768).toShort(), InputNormalizer.axis(-1f))
        assertEquals(32767.toShort(), InputNormalizer.axis(5f))
        assertEquals(0.toShort(), InputNormalizer.axis(0f))
    }

    @Test
    fun stickUpIsPositiveY() {
        val out = ShortArray(2)
        InputNormalizer.stick(0f, -100f, 100f, out)
        assertEquals(0.toShort(), out[0])
        assertEquals(32767.toShort(), out[1])
    }

    @Test
    fun deadzoneAndClamp() {
        val out = ShortArray(2)
        InputNormalizer.stick(5f, 0f, 100f, out)
        assertEquals(0.toShort(), out[0])

        InputNormalizer.stick(500f, 0f, 100f, out)
        assertEquals(32767.toShort(), out[0])
        assertFalse(out[1] != 0.toShort())
    }
}
