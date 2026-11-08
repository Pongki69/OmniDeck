package com.remotegamestreamer.client

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.input.TouchHudOverlay

/** Drag controls to move them. Save keeps the changes, the Back button discards them. */
class LayoutEditorActivity : Activity() {
    private lateinit var hud: TouchHudOverlay
    private lateinit var layoutModel: HudLayout
    private lateinit var hide: Button
    private lateinit var smaller: Button
    private lateinit var bigger: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        layoutModel = settings.layout

        hud = TouchHudOverlay(this, layoutModel, settings.opacityPercent, settings.stickLocked, settings.cameraSensitivityPercent, editMode = true)

        smaller = tool("−") { hud.resizeSelected(-RESIZE_STEP) }
        bigger = tool("+") { hud.resizeSelected(RESIZE_STEP) }
        hide = tool("Hide") { hud.toggleSelectedVisible() }
        val reset = tool("Reset") { hud.resetLayout() }
        val save = tool("Save") {
            settings.layout = layoutModel
            finish()
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            addView(TextView(this@LayoutEditorActivity).apply {
                text = "Drag to move. Tap a control, then resize or hide it."
                gravity = Gravity.CENTER
            })
            addView(LinearLayout(this@LayoutEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(smaller)
                addView(bigger)
                addView(hide)
                addView(reset)
                addView(save)
            })
        }

        hud.onSelectionChanged = { id -> updateTools(id) }
        updateTools(null)

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(25, 25, 25))
                addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
        )
        hideSystemBars()
    }

    private fun tool(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        minWidth = 0
        setOnClickListener { onClick() }
    }

    private fun updateTools(id: String?) {
        smaller.isEnabled = id != null
        bigger.isEnabled = id != null
        hide.isEnabled = id != null && layoutModel.canHide(id)
        hide.text = if (id != null && !layoutModel.placement(id).visible) "Show" else "Hide"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private companion object {
        const val RESIZE_STEP = 0.1f
    }
}
