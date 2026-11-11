package com.remotegamestreamer.client.input

import kotlin.math.hypot
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

    fun clampToShort(value: Int): Short = value.coerceIn(-32768, 32767).toShort()
}

/**
 * Turns swipe distance into right-stick movement. A stick can't go past full deflection, so travel
 * above that is kept and delivered by holding the stick a little longer, up to [maxPending] ms of
 * full stick. Without this, higher sensitivity would stop having any effect on a normal swipe.
 * Not thread-safe: the caller locks.
 */
class CameraMotion(private val smoothingMs: Float = 12f, private val maxPending: Float = 400f) {
    private var pendingX = 0f
    private var pendingY = 0f

    fun clear() {
        pendingX = 0f
        pendingY = 0f
    }

    /** Adds a swipe in dp, with Y already pointing up. Gains are stick deflection per dp/ms. */
    fun add(dxDp: Float, dyDp: Float, gainX: Float, gainY: Float = gainX) {
        pendingX += dxDp * gainX
        pendingY += dyDp * gainY
        clampPending()
    }

    /** Converts continuous stick deflection into relative motion for the sample interval. */
    fun addDeflection(x: Float, y: Float, elapsedMs: Float) {
        val elapsed = elapsedMs.coerceIn(0f, 12f)
        pendingX += x.coerceIn(-1f, 1f) * elapsed
        pendingY += y.coerceIn(-1f, 1f) * elapsed
        clampPending()
    }

    private fun clampPending() {
        val distance = hypot(pendingX, pendingY)
        if (distance > maxPending) {
            pendingX *= maxPending / distance
            pendingY *= maxPending / distance
        }
    }

    /** Writes the stick position for this moment into [out] (x, y) and uses up that much travel. */
    fun step(elapsedMs: Float, out: ShortArray) {
        var x = pendingX / smoothingMs
        var y = pendingY / smoothingMs
        val deflection = hypot(x, y)

        if (deflection < STOP_BELOW) {
            pendingX = 0f
            pendingY = 0f
            out[0] = 0
            out[1] = 0
            return
        }

        if (deflection > 1f) { // scale both axes together so the direction doesn't bend
            x /= deflection
            y /= deflection
        }

        val used = elapsedMs.coerceIn(0f, smoothingMs)
        pendingX -= x * used
        pendingY -= y * used
        out[0] = InputNormalizer.axis(x)
        out[1] = InputNormalizer.axis(y)
    }

    private companion object {
        const val STOP_BELOW = 0.01f
    }
}
