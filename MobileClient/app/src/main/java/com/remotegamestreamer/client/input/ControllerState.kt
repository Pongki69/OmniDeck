package com.remotegamestreamer.client.input

/** Mirror of the host's 17-byte ControllerStatePacket. */
class ControllerState {
    var buttons = 0
    var leftTrigger = 0
    var rightTrigger = 0
    var thumbLX: Short = 0
    var thumbLY: Short = 0
    var thumbRX: Short = 0
    var thumbRY: Short = 0
    var mouseDeltaX: Short = 0
    var mouseDeltaY: Short = 0
    var mouseButtons = 0

    companion object {
        const val SIZE = 17

        const val A = 1 shl 0
        const val B = 1 shl 1
        const val X = 1 shl 2
        const val Y = 1 shl 3
        const val LB = 1 shl 4
        const val RB = 1 shl 5
        const val START = 1 shl 6
        const val SELECT = 1 shl 7
        const val L3 = 1 shl 8
        const val R3 = 1 shl 9
        const val DPAD_UP = 1 shl 10
        const val DPAD_DOWN = 1 shl 11
        const val DPAD_LEFT = 1 shl 12
        const val DPAD_RIGHT = 1 shl 13

        const val MOUSE_LEFT = 1
        const val MOUSE_RIGHT = 2
    }
}
