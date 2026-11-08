package com.remotegamestreamer.client.input

import kotlin.math.sqrt

/** Maps screen touches to signed XInput axis bounds. */
object InputNormalizer {
    const val STICK_DEADZONE = 0.08f

    /** Maps -1..1 onto the full asymmetric short range. */
    fun axis(value: Float): Short {
        val v = value.coerceIn(-1f, 1f)
        return (if (v >= 0f) v * 32767f else v * 32768f).toInt().toShort()
    }

    /**
     * Converts a thumb offset from the stick's center to axis values in [out] (x, y).
     * Screen Y grows downward and XInput Y grows upward, so Y is flipped.
     */
    fun stick(dx: Float, dy: Float, radius: Float, out: ShortArray) {
        val nx = dx / radius
        val ny = dy / radius
        val magnitude = sqrt(nx * nx + ny * ny)

        if (magnitude < STICK_DEADZONE) {
            out[0] = 0
            out[1] = 0
            return
        }

        // Rescale so output starts at 0 just outside the deadzone and reaches 1 at the rim.
        val scaled = ((magnitude - STICK_DEADZONE) / (1f - STICK_DEADZONE)).coerceAtMost(1f) / magnitude
        out[0] = axis(nx * scaled)
        out[1] = axis(-ny * scaled)
    }

    /** Camera drag speed (dp per ms, screen direction) to one right-stick axis. */
    fun camera(dpPerMs: Float, gain: Float = 0.75f): Short = axis(dpPerMs * gain)

    fun clampToShort(value: Int): Short = value.coerceIn(-32768, 32767).toShort()
}
