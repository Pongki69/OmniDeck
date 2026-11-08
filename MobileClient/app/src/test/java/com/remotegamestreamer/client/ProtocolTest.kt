package com.remotegamestreamer.client

import com.remotegamestreamer.client.decoder.FrameReassembler
import com.remotegamestreamer.client.input.ControlKind
import com.remotegamestreamer.client.input.ControllerState
import com.remotegamestreamer.client.input.HudCatalog
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.input.InputNormalizer
import com.remotegamestreamer.client.net.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
