package com.remotegamestreamer.client.input

enum class ControlKind { BIT, LEFT_TRIGGER, RIGHT_TRIGGER, TOUCHPAD_TOGGLE, STICK }

class ControlSpec(
    val id: String,
    val label: String,
    val kind: ControlKind,
    val bit: Int,
    val defaultX: Float,
    val defaultY: Float,
    val radiusDp: Float,
)

/** Every on-screen control and where it starts. Positions are fractions of the screen. */
object HudCatalog {
    private fun bit(id: String, label: String, bit: Int, x: Float, y: Float, r: Float) =
        ControlSpec(id, label, ControlKind.BIT, bit, x, y, r)

    val controls: List<ControlSpec> = listOf(
        ControlSpec("stick", "", ControlKind.STICK, 0, 0.16f, 0.70f, 64f),
        bit("a", "A", ControllerState.A, 0.88f, 0.82f, 32f),
        bit("b", "B", ControllerState.B, 0.95f, 0.62f, 32f),
        bit("x", "X", ControllerState.X, 0.81f, 0.62f, 32f),
        bit("y", "Y", ControllerState.Y, 0.88f, 0.42f, 32f),
        ControlSpec("rt", "RT", ControlKind.RIGHT_TRIGGER, 0, 0.93f, 0.17f, 30f),
        bit("rb", "RB", ControllerState.RB, 0.83f, 0.17f, 30f),
        bit("lb", "LB", ControllerState.LB, 0.07f, 0.17f, 30f),
        ControlSpec("lt", "LT", ControlKind.LEFT_TRIGGER, 0, 0.17f, 0.17f, 30f),
        bit("l3", "L3", ControllerState.L3, 0.27f, 0.88f, 26f),
        bit("r3", "R3", ControllerState.R3, 0.71f, 0.88f, 26f),
        bit("start", "Start", ControllerState.START, 0.70f, 0.08f, 24f),
        bit("select", "Select", ControllerState.SELECT, 0.30f, 0.08f, 24f),
        bit("dleft", "◀", ControllerState.DPAD_LEFT, 0.38f, 0.08f, 20f),
        bit("dup", "▲", ControllerState.DPAD_UP, 0.46f, 0.08f, 20f),
        bit("ddown", "▼", ControllerState.DPAD_DOWN, 0.54f, 0.08f, 20f),
        bit("dright", "▶", ControllerState.DPAD_RIGHT, 0.62f, 0.08f, 20f),
        ControlSpec("pad", "Pad", ControlKind.TOUCHPAD_TOGGLE, 0, 0.50f, 0.24f, 24f),
    )
}

class Placement(var fx: Float, var fy: Float, var scale: Float, var visible: Boolean)

/** The player's arrangement of the controls. Contains no Android types, so it can be unit tested. */
class HudLayout {
    private val specs = HudCatalog.controls.associateBy { it.id }
    private val placements = specs.mapValues { (_, s) -> Placement(s.defaultX, s.defaultY, 1f, true) }

    fun placement(id: String): Placement = placements.getValue(id)

    fun move(id: String, fx: Float, fy: Float) {
        placement(id).apply {
            this.fx = fx.coerceIn(0f, 1f)
            this.fy = fy.coerceIn(0f, 1f)
        }
    }

    fun resize(id: String, delta: Float) {
        placement(id).apply { scale = (scale + delta).coerceIn(MIN_SCALE, MAX_SCALE) }
    }

    /** The stick can't be hidden: without it there would be no way to move in-game. */
    fun canHide(id: String) = specs.getValue(id).kind != ControlKind.STICK

    fun toggleVisible(id: String) {
        if (canHide(id)) placement(id).apply { visible = !visible }
    }

    fun reset() {
        for ((id, s) in specs) {
            placement(id).apply {
                fx = s.defaultX
                fy = s.defaultY
                scale = 1f
                visible = true
            }
        }
    }

    fun serialize(): String = placements.entries.joinToString(";") { (id, p) ->
        "$id,${p.fx},${p.fy},${p.scale},${if (p.visible) 1 else 0}"
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f

        /** Tolerates missing or damaged data: anything unreadable keeps its default. */
        fun parse(text: String?): HudLayout {
            val layout = HudLayout()
            for (entry in text.orEmpty().split(';')) {
                val f = entry.split(',')
                if (f.size != 5 || f[0] !in layout.placements) continue
                val x = f[1].toFloatOrNull() ?: continue
                val y = f[2].toFloatOrNull() ?: continue
                val scale = f[3].toFloatOrNull() ?: continue
                if (!x.isFinite() || !y.isFinite() || !scale.isFinite()) continue

                layout.move(f[0], x, y)
                layout.placement(f[0]).scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
                layout.placement(f[0]).visible = f[4] != "0" || !layout.canHide(f[0])
            }
            return layout
        }
    }
}
