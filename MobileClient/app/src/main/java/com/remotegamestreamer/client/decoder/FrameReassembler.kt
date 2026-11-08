package com.remotegamestreamer.client.decoder

import com.remotegamestreamer.client.net.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rebuilds encoded frames from video shards, repairing one lost shard per parity group.
 * Mirrors HostServer/Network/VideoPacketizer.cs. Not thread-safe: one receiver thread only.
 */
class FrameReassembler(
    private val payloadSize: Int = DEFAULT_PAYLOAD,
    private val onFrame: (data: ByteArray, length: Int, keyframe: Boolean) -> Unit,
) {
    private class Slot {
        var id = -1L
        var frameLength = 0
        var dataShards = 0
        var keyframe = false
        var received = 0
        var done = false
        var data = ByteArray(0)
        var present = BooleanArray(0)
        var parity = ByteArray(0)
        var parityPresent = BooleanArray(0)
    }

    private val slots = Array(SLOTS) { Slot() }
    private var lastDelivered = -1L
    private var waitingForKeyframe = true

    var lostFrames = 0L
        private set
    var repairedShards = 0L
        private set

    fun push(d: ByteArray, length: Int) {
        if (length < HEADER_SIZE || !Protocol.hasHeader(d, length, Protocol.TYPE_VIDEO)) return

        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.getInt(4).toLong() and 0xFFFFFFFFL
        val frameLength = b.getInt(8)
        val index = b.getShort(12).toInt() and 0xFFFF
        val dataShards = b.getShort(14).toInt() and 0xFFFF
        val flags = b.get(16).toInt()
        val bodyLength = length - HEADER_SIZE

        if (dataShards == 0 || frameLength <= 0 || frameLength > dataShards.toLong() * payloadSize || bodyLength > payloadSize) return
        if (frameLength > MAX_FRAME_BYTES) return
        if (id <= lastDelivered) return

        val groups = (dataShards + GROUP_SIZE - 1) / GROUP_SIZE
        val isParity = flags and FLAG_PARITY != 0
        if (isParity && index !in dataShards until dataShards + groups) return
        if (!isParity && index >= dataShards) return

        val slot = slots[(id % SLOTS).toInt()]
        if (slot.id != id) {
            if (slot.id > id) return // a newer frame owns this slot
            reset(slot, id, frameLength, dataShards, groups, flags and FLAG_KEYFRAME != 0)
        }
        if (slot.done || slot.frameLength != frameLength || slot.dataShards != dataShards) return

        if (isParity) {
            val group = index - dataShards
            if (slot.parityPresent[group]) return
            slot.parityPresent[group] = true
            System.arraycopy(d, HEADER_SIZE, slot.parity, group * payloadSize, bodyLength)
            tryRepair(slot, group)
        } else {
            if (slot.present[index]) return
            slot.present[index] = true
            slot.received++
            System.arraycopy(d, HEADER_SIZE, slot.data, index * payloadSize, bodyLength)
            tryRepair(slot, index / GROUP_SIZE)
        }

        if (slot.received == slot.dataShards) deliver(slot)
    }

    private fun reset(s: Slot, id: Long, frameLength: Int, dataShards: Int, groups: Int, keyframe: Boolean) {
        s.id = id
        s.frameLength = frameLength
        s.dataShards = dataShards
        s.keyframe = keyframe
        s.received = 0
        s.done = false

        val capacity = dataShards * payloadSize
        if (s.data.size < capacity) s.data = ByteArray(capacity)
        // Parity is computed over zero-padded shards, so the tail must really be zero.
        s.data.fill(0, frameLength, capacity)

        if (s.present.size < dataShards) s.present = BooleanArray(dataShards) else s.present.fill(false, 0, dataShards)

        val parityBytes = groups * payloadSize
        if (s.parity.size < parityBytes) s.parity = ByteArray(parityBytes)
        if (s.parityPresent.size < groups) s.parityPresent = BooleanArray(groups) else s.parityPresent.fill(false, 0, groups)
    }

    private fun tryRepair(s: Slot, group: Int) {
        if (!s.parityPresent[group]) return

        val first = group * GROUP_SIZE
        val end = minOf(first + GROUP_SIZE, s.dataShards)
        var missing = -1
        for (i in first until end) {
            if (!s.present[i]) {
                if (missing != -1) return // two losses: not repairable
                missing = i
            }
        }
        if (missing == -1) return

        val target = missing * payloadSize
        val parityStart = group * payloadSize
        for (k in 0 until payloadSize) {
            var v = s.parity[parityStart + k].toInt()
            for (i in first until end) {
                if (i != missing) v = v xor s.data[i * payloadSize + k].toInt()
            }
            // The final shard may be short, and only its real bytes belong to the frame.
            if (target + k < s.frameLength) s.data[target + k] = v.toByte()
        }
        s.present[missing] = true
        s.received++
        repairedShards++
    }

    private fun deliver(s: Slot) {
        s.done = true
        if (s.id <= lastDelivered) return

        if (lastDelivered >= 0) lostFrames += s.id - lastDelivered - 1
        lastDelivered = s.id

        if (waitingForKeyframe) {
            if (!s.keyframe) return
            waitingForKeyframe = false
        }
        // After an unrepairable loss we keep feeding the decoder: brief artifacts until
        // the next IDR (at most 1 s) are better for play than a frozen picture.
        onFrame(s.data, s.frameLength, s.keyframe)
    }

    companion object {
        const val HEADER_SIZE = 18
        const val DEFAULT_PAYLOAD = 1200
        const val GROUP_SIZE = 8
        const val FLAG_KEYFRAME = 1
        const val FLAG_PARITY = 2
        private const val SLOTS = 16
        private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    }
}
