package com.remotegamestreamer.client

import android.app.Activity
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val padding = (20 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        heading("Stream")
        val heights = AppSettings.RESOLUTIONS
        spinner("Resolution (never above your PC's)", heights.map { "${it}p" }, heights.indexOf(settings.maxHeight).coerceAtLeast(0)) {
            settings.maxHeight = heights[it]
        }
        val rates = AppSettings.FRAME_RATES
        spinner("Frame rate", rates.map { "$it FPS" }, rates.indexOf(settings.fps).coerceAtLeast(0)) {
            settings.fps = rates[it]
        }
        slider("Bitrate", AppSettings.MIN_BITRATE, AppSettings.MAX_BITRATE, settings.bitrateMbps, { "$it Mbps" }) {
            settings.bitrateMbps = it
        }
        if (phoneDecodesHevc()) {
            spinner("Video codec", listOf("H.264 (most compatible)", "HEVC (sharper at low bitrate)"), if (settings.hevc) 1 else 0) {
                settings.hevc = it == 1
            }
        } else {
            settings.hevc = false
        }
        toggle("Sound", settings.sound) { settings.sound = it }

        heading("Controls")
        toggle("Lock joystick in place (off: it appears where you touch)", settings.stickLocked) { settings.stickLocked = it }
        slider("Control opacity", 10, 100, settings.opacityPercent, { "$it%" }) { settings.opacityPercent = it }
        slider("Camera sensitivity", 25, 200, settings.cameraSensitivityPercent, { "$it%" }) { settings.cameraSensitivityPercent = it }
        column.addView(
            Button(this).apply {
                text = "Edit control layout"
                setOnClickListener { startActivity(Intent(this@SettingsActivity, LayoutEditorActivity::class.java)) }
            },
        )

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun phoneDecodesHevc(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }

    private fun heading(title: String) {
        column.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })
    }

    private fun caption(title: String) = TextView(this).apply {
        text = title
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }.also { column.addView(it) }

    private fun spinner(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        caption(title)
        column.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val label = caption("$title: ${format(value)}")
        column.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title: ${format(min + progress)}"
                    if (fromUser) onChange(min + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        column.addView(Switch(this).apply {
            text = title
            isChecked = checked
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
    }
}
