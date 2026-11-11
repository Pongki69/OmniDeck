package com.remotegamestreamer.client.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** Standard Android gamepad events only; contains no game-specific behavior or automation. */
class PhysicalGamepadState {
    private val lock = Any()
    private val heldKeys = HashSet<Int>()
    private var buttons = 0
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var lt = 0f
    private var rt = 0f
    private var hatX = 0f
    private var hatY = 0f

    fun handleKey(event: KeyEvent, down: Boolean): Boolean {
        if (!isGamepad(event.source)) return false
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            synchronized(lock) {
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) lt = if (down) 1f else 0f
                else rt = if (down) 1f else 0f
            }
            return true
        }
        if (keyBit(event.keyCode) == null) return false
        synchronized(lock) {
            if (down) heldKeys.add(event.keyCode) else heldKeys.remove(event.keyCode)
            buttons = heldKeys.mapNotNull(::keyBit).fold(0) { value, next -> value or next }
        }
        return true
    }

    fun handleMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.source) || event.action != MotionEvent.ACTION_MOVE) return false
        val range = event.device?.let { device ->
            fun axis(code: Int) = device.getMotionRange(code, event.source)?.let { event.getAxisValue(code).coerceIn(it.min, it.max) } ?: 0f
            val rightX = if (device.getMotionRange(MotionEvent.AXIS_RX, event.source) != null) axis(MotionEvent.AXIS_RX) else axis(MotionEvent.AXIS_Z)
            val rightY = if (device.getMotionRange(MotionEvent.AXIS_RY, event.source) != null) axis(MotionEvent.AXIS_RY) else axis(MotionEvent.AXIS_RZ)
            floatArrayOf(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y), rightX, rightY, axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_HAT_X), axis(MotionEvent.AXIS_HAT_Y))
        } ?: return false
        synchronized(lock) {
            lx = deadzone(range[0]); ly = deadzone(range[1]); rx = deadzone(range[2]); ry = deadzone(range[3])
            lt = trigger(range[4]); rt = trigger(range[5]); hatX = range[6]; hatY = range[7]
        }
        return true
    }

    fun merge(out: ControllerState, includeRightStick: Boolean = true) = synchronized(lock) {
        out.buttons = out.buttons or buttons
        if (abs(lx) > 0f) out.thumbLX = InputNormalizer.axis(lx)
        if (abs(ly) > 0f) out.thumbLY = InputNormalizer.axis(-ly)
        if (includeRightStick && abs(rx) > 0f) out.thumbRX = InputNormalizer.axis(rx)
        if (includeRightStick && abs(ry) > 0f) out.thumbRY = InputNormalizer.axis(-ry)
        out.leftTrigger = maxOf(out.leftTrigger, (lt * 255).toInt())
        out.rightTrigger = maxOf(out.rightTrigger, (rt * 255).toInt())
        if (hatX < -0.5f) out.buttons = out.buttons or ControllerState.DPAD_LEFT
        if (hatX > 0.5f) out.buttons = out.buttons or ControllerState.DPAD_RIGHT
        if (hatY < -0.5f) out.buttons = out.buttons or ControllerState.DPAD_UP
        if (hatY > 0.5f) out.buttons = out.buttons or ControllerState.DPAD_DOWN
    }

    fun rightStickAxes(): Pair<Float, Float> = synchronized(lock) { rx to -ry }

    fun isRightStickClickPressed(): Boolean = synchronized(lock) { (buttons and ControllerState.R3) != 0 }

    private fun isGamepad(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    private fun deadzone(v: Float) = if (abs(v) < STICK_DEADZONE) 0f else v
    private fun trigger(v: Float) = if (v < 0f) (v + 1f) / 2f else v

    private fun keyBit(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> ControllerState.A
        KeyEvent.KEYCODE_BUTTON_B -> ControllerState.B
        KeyEvent.KEYCODE_BUTTON_X -> ControllerState.X
        KeyEvent.KEYCODE_BUTTON_Y -> ControllerState.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.RB
        KeyEvent.KEYCODE_BUTTON_START -> ControllerState.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.SELECT
        KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.GUIDE
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.R3
        KeyEvent.KEYCODE_DPAD_UP -> ControllerState.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.DPAD_RIGHT
        else -> null
    }

    private companion object { const val STICK_DEADZONE = 0.12f }
}
