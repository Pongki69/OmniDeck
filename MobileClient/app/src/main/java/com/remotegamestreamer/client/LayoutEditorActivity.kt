package com.remotegamestreamer.client

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
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
    private lateinit var imageButton: Button
    private lateinit var clearImageButton: Button
    private lateinit var groupButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        layoutModel = settings.layout

        hud = TouchHudOverlay(this, layoutModel, settings.opacityPercent, settings.stickLocked, settings.cameraSensitivityPercent, settings.cameraSensitivityYPercent, editMode = true)

        smaller = tool("−") { hud.resizeSelected(-RESIZE_STEP) }
        bigger = tool("+") { hud.resizeSelected(RESIZE_STEP) }
        hide = tool("Hide") { hud.toggleSelectedVisible() }
        imageButton = tool("Choose image") { chooseImage() }
        clearImageButton = tool("Clear image") {
            hud.setSelectedImage(null)
            updateTools(hud.selectedControlId)
        }
        groupButton = tool("Move to More") {
            hud.toggleSelectedGroup()
            updateTools(hud.selectedControlId)
        }
        val reset = tool("Reset") { hud.resetLayout() }
        val save = tool("Save") {
            settings.layout = layoutModel
            finish()
        }

        val presets = Spinner(this).apply {
            adapter = ArrayAdapter(this@LayoutEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Preset: Full", "Preset: Compact", "Preset: Left-handed"))
            setSelection(0)
            var skipInitialSelection = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (skipInitialSelection) { skipInitialSelection = false; return }
                    hud.applyPreset(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            addView(TextView(this@LayoutEditorActivity).apply {
                text = "Drag to move. Tap a control, then resize or hide it."
                gravity = Gravity.CENTER
            })
            addView(presets)
            addView(LinearLayout(this@LayoutEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(smaller)
                addView(bigger)
                addView(hide)
                addView(reset)
                addView(save)
            })
            addView(LinearLayout(this@LayoutEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(imageButton)
                addView(clearImageButton)
                addView(groupButton)
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
        imageButton.isEnabled = id != null && id != "stick"
        clearImageButton.isEnabled = id != null && id != "stick" && layoutModel.placement(id).imageUri != null
        groupButton.isEnabled = id != null && id != "more" && id != "stick"
        groupButton.text = if (id != null && layoutModel.placement(id).inMore) "Move to main" else "Move to More"
    }

    private fun chooseImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_IMAGE)
    }

    @Deprecated("Uses the platform document picker for persistent image access")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        hud.setSelectedImage(uri.toString())
        updateTools(hud.selectedControlId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private companion object {
        const val RESIZE_STEP = 0.1f
        const val REQUEST_IMAGE = 41
    }
}
