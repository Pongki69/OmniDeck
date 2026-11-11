package com.remotegamestreamer.client.input

import java.util.Base64

enum class ControlKind { BIT, LEFT_TRIGGER, RIGHT_TRIGGER, TOUCHPAD_TOGGLE, KEYBOARD, CAMERA_SENSITIVITY, STICK, RIGHT_STICK, GROUP_TOGGLE, HUD_TOGGLE }

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
        ControlSpec("stick", "", ControlKind.STICK, 0, 0.16f, 0.78f, 64f),
        ControlSpec("rightStick", "", ControlKind.RIGHT_STICK, 0, 0.84f, 0.78f, 64f),
        bit("a", "A", ControllerState.A, 0.88f, 0.78f, 32f),
        bit("b", "B", ControllerState.B, 0.95f, 0.62f, 32f),
        bit("x", "X", ControllerState.X, 0.81f, 0.62f, 32f),
        bit("y", "Y", ControllerState.Y, 0.88f, 0.46f, 32f),
        ControlSpec("rt", "RT", ControlKind.RIGHT_TRIGGER, 0, 0.86f, 0.20f, 30f),
        bit("rb", "RB", ControllerState.RB, 0.75f, 0.20f, 30f),
        bit("lb", "LB", ControllerState.LB, 0.25f, 0.20f, 30f),
        ControlSpec("lt", "LT", ControlKind.LEFT_TRIGGER, 0, 0.14f, 0.20f, 30f),
        bit("l3", "L3", ControllerState.L3, 0.29f, 0.84f, 26f),
        bit("r3", "R3", ControllerState.R3, 0.71f, 0.84f, 26f),
        bit("start", "Start", ControllerState.START, 0.57f, 0.14f, 24f),
        bit("select", "Select", ControllerState.SELECT, 0.43f, 0.14f, 24f),
        bit("dleft", "◀", ControllerState.DPAD_LEFT, 0.09f, 0.52f, 20f),
        bit("dup", "▲", ControllerState.DPAD_UP, 0.15f, 0.46f, 20f),
        bit("ddown", "▼", ControllerState.DPAD_DOWN, 0.15f, 0.58f, 20f),
        bit("dright", "▶", ControllerState.DPAD_RIGHT, 0.21f, 0.52f, 20f),
        bit("guide", "Guide", ControllerState.GUIDE, 0.50f, 0.28f, 24f),
        ControlSpec("pad", "Pad", ControlKind.TOUCHPAD_TOGGLE, 0, 0.50f, 0.42f, 24f),
        ControlSpec("kbd", "Kbd", ControlKind.KEYBOARD, 0, 0.40f, 0.28f, 24f),
        ControlSpec("sens", "Sens", ControlKind.CAMERA_SENSITIVITY, 0, 0.60f, 0.28f, 24f),
        ControlSpec("hud", "", ControlKind.HUD_TOGGLE, 0, 0.40f, 0.94f, 27f),
        ControlSpec("more", "", ControlKind.GROUP_TOGGLE, 0, 0.93f, 0.88f, 27f),
        ControlSpec("drawer2", "", ControlKind.GROUP_TOGGLE, 0, 0.07f, 0.84f, 27f),
        ControlSpec("drawer3", "", ControlKind.GROUP_TOGGLE, 0, 0.93f, 0.12f, 27f),
        ControlSpec("drawer4", "", ControlKind.GROUP_TOGGLE, 0, 0.07f, 0.12f, 27f),
        ControlSpec("drawer5", "", ControlKind.GROUP_TOGGLE, 0, 0.50f, 0.92f, 27f),
        ControlSpec("drawer6", "", ControlKind.GROUP_TOGGLE, 0, 0.50f, 0.08f, 27f),
    )
}

class Placement(
    var fx: Float,
    var fy: Float,
    var scale: Float,
    var visible: Boolean,
    var imageUri: String? = null,
    var inMore: Boolean = false,
    var drawerId: String? = null,
    var customLabel: String? = null,
    var iconPreset: String = "default",
    var customSvg: String? = null,
    var anchorLocked: Boolean = false,
    var color: Int = 0xFFFFFFFF.toInt(),
    var shape: Int = 0,
    var opacity: Int = 100,
)

/** The player's arrangement of the controls. Contains no Android types, so it can be unit tested. */
class HudLayout {
    private val specs = HudCatalog.controls.associateBy { it.id }
    private val placements = specs.mapValues { (id, s) ->
        Placement(s.defaultX, s.defaultY, 1f, true).apply {
            iconPreset = when { id == "more" -> "drawer"; id == "hud" -> "eye"; id.startsWith("drawer") -> "folder"; else -> "default" }
            visible = id == "more" || !id.startsWith("drawer")
            anchorLocked = id == "more"
        }
    }

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

    /** Every overlay control can be hidden; hidden controls remain recoverable from the editor. */
    fun canHide(id: String) = id in specs

    fun toggleVisible(id: String) {
        if (canHide(id)) placement(id).apply { visible = !visible }
    }

    fun setAllVisible(visible: Boolean) {
        placements.keys.filter { id ->
            canHide(id) && (!id.startsWith("drawer") || placement(id).customLabel != null)
        }.forEach { placement(it).visible = visible }
    }

    fun createDrawer(): String? {
        val id = (2..6).asSequence().map { "drawer$it" }
            .firstOrNull { placement(it).customLabel == null } ?: return null
        val slot = id.removePrefix("drawer").toInt()
        placement(id).apply {
            visible = true
            fx = if (slot % 2 == 0) .92f else .08f
            fy = when (slot) { 2 -> .80f; 3 -> .18f; 4 -> .18f; 5 -> .91f; else -> .09f }
            customLabel = "Drawer $slot"
            iconPreset = "folder"
            anchorLocked = false
            drawerId = null
            inMore = false
        }
        return id
    }

    fun drawerIds(): List<String> = listOf("more") + (2..6).map { "drawer$it" }
        .filter { placement(it).customLabel != null }

    fun drawerName(id: String): String = when (id) {
        "more" -> "Collapsible HUD Drawer"
        else -> placement(id).customLabel?.takeIf { it.isNotBlank() } ?: "Drawer ${id.removePrefix("drawer")}"
    }

    /** Deletes a user-created drawer and returns its controls to the main HUD without losing them. */
    fun deleteDrawer(id: String): Boolean {
        if (id == "more" || !id.startsWith("drawer")) return false
        val launcher = placement(id)
        if (launcher.customLabel == null) return false
        placements.forEach { (controlId, control) ->
            if (control.drawerId == id) setDrawerId(controlId, null)
        }
        val spec = specs.getValue(id)
        launcher.apply {
            fx = spec.defaultX
            fy = spec.defaultY
            scale = 1f
            visible = false
            imageUri = null
            inMore = false
            drawerId = null
            customLabel = null
            iconPreset = "folder"
            customSvg = null
            anchorLocked = false
            color = 0xFFFFFFFF.toInt()
            shape = 0
            opacity = 100
        }
        return true
    }

    fun setDrawerId(controlId: String, drawerId: String?) {
        placement(controlId).apply {
            this.drawerId = drawerId
            inMore = drawerId == "more"
        }
    }

    fun copyFrom(other: HudLayout) {
        for (id in placements.keys) {
            val source = other.placement(id)
            placement(id).apply {
                fx = source.fx; fy = source.fy; scale = source.scale; visible = source.visible
                imageUri = source.imageUri; inMore = source.inMore; drawerId = source.drawerId; customLabel = source.customLabel
                iconPreset = source.iconPreset; customSvg = source.customSvg; anchorLocked = source.anchorLocked
                color = source.color; shape = source.shape; opacity = source.opacity
            }
        }
    }

    fun reset() {
        for ((id, s) in specs) {
            placement(id).apply {
                fx = s.defaultX
                fy = s.defaultY
                scale = 1f
                visible = id == "more" || !id.startsWith("drawer")
                imageUri = null
                inMore = false
                drawerId = null
                customLabel = null
                iconPreset = when { id == "more" -> "drawer"; id == "hud" -> "eye"; id.startsWith("drawer") -> "folder"; else -> "default" }
                customSvg = null
                anchorLocked = id == "more"
                color = 0xFFFFFFFF.toInt()
                shape = 0
                opacity = 100
            }
        }
    }

    fun applyPreset(preset: Int) {
        reset()
        when (preset) {
            PRESET_COMPACT -> listOf("dleft", "dup", "ddown", "dright", "guide", "l3", "r3", "pad", "kbd", "sens").forEach { placement(it).visible = false }
            PRESET_LEFT_HANDED -> for (control in HudCatalog.controls) {
                val p = placement(control.id)
                p.fx = 1f - p.fx
            }
        }
    }

    fun serialize(): String = placements.entries.joinToString(";") { (id, p) ->
        val image = p.imageUri?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        val label = p.customLabel?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        val svg = p.customSvg?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        val drawer = p.drawerId?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        "$id,${p.fx},${p.fy},${p.scale},${if (p.visible) 1 else 0},$image,${if (p.inMore || p.drawerId != null) 1 else 0},${p.color},${p.shape},${p.opacity},$label,${p.iconPreset.take(24)},$svg,${if (p.anchorLocked) 1 else 0},$drawer"
    }

    companion object {
        const val PRESET_DEFAULT = 0
        const val PRESET_COMPACT = 1
        const val PRESET_LEFT_HANDED = 2
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        val ICON_PRESETS = setOf("default", "more", "drawer", "folder", "eye", "gamepad", "keyboard", "back", "camera", "target", "star")

        /** Tolerates missing or damaged data: anything unreadable keeps its default. */
        fun parse(text: String?): HudLayout {
            val layout = HudLayout()
            for (entry in text.orEmpty().split(';')) {
                val f = entry.split(',')
                if (f.size !in 5..15 || f[0] !in layout.placements) continue
                val x = f[1].toFloatOrNull() ?: continue
                val y = f[2].toFloatOrNull() ?: continue
                val scale = f[3].toFloatOrNull() ?: continue
                if (!x.isFinite() || !y.isFinite() || !scale.isFinite()) continue

                layout.move(f[0], x, y)
                layout.placement(f[0]).scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
                layout.placement(f[0]).visible = f[4] != "0" || !layout.canHide(f[0])
                if (f.size >= 6 && f[5].isNotEmpty()) {
                    layout.placement(f[0]).imageUri = runCatching {
                        String(Base64.getUrlDecoder().decode(f[5]), Charsets.UTF_8)
                    }.getOrNull()
                }
                if (f.size >= 7) layout.placement(f[0]).inMore = f[6] == "1"
                if (f.size >= 8) layout.placement(f[0]).color = f[7].toIntOrNull() ?: 0xFFFFFFFF.toInt()
                if (f.size >= 9) layout.placement(f[0]).shape = (f[8].toIntOrNull() ?: 0).coerceIn(0, 2)
                if (f.size >= 10) layout.placement(f[0]).opacity = (f[9].toIntOrNull() ?: 100).coerceIn(10, 100)
                if (f.size >= 11 && f[10].isNotEmpty()) {
                    layout.placement(f[0]).customLabel = runCatching {
                        String(Base64.getUrlDecoder().decode(f[10]), Charsets.UTF_8).take(18)
                    }.getOrNull()
                }
                if (f.size >= 12 && f[11] in ICON_PRESETS) {
                    layout.placement(f[0]).iconPreset = if (f[0] == "more" && f[11] == "more") "drawer" else f[11]
                }
                if (f.size >= 13 && f[12].isNotEmpty()) {
                    layout.placement(f[0]).customSvg = runCatching {
                        String(Base64.getUrlDecoder().decode(f[12]), Charsets.UTF_8).take(16 * 1024)
                    }.getOrNull()
                }
                if (f.size >= 14) layout.placement(f[0]).anchorLocked = f[13] == "1"
                if (f.size >= 15 && f[14].isNotEmpty()) {
                    layout.placement(f[0]).drawerId = runCatching {
                        String(Base64.getUrlDecoder().decode(f[14]), Charsets.UTF_8).take(24)
                    }.getOrNull()
                    if (layout.placement(f[0]).drawerId != null) layout.placement(f[0]).inMore = false
                } else if (layout.placement(f[0]).inMore) {
                    // Upgrade existing layouts: their single More group becomes drawer "more".
                    layout.placement(f[0]).drawerId = "more"
                }
            }
            return layout
        }
    }
}
