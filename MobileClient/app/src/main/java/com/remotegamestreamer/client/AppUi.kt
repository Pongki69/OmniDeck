package com.remotegamestreamer.client

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Shared visual tokens and touch feedback for the native Android screens. */
object AppUi {
    val background = Color.rgb(13, 17, 24)
    val surface = Color.rgb(24, 31, 42)
    val elevatedSurface = Color.rgb(33, 42, 55)
    val accent = Color.rgb(71, 211, 177)
    val textPrimary = Color.rgb(241, 245, 250)
    val textSecondary = Color.rgb(164, 178, 194)

    fun styleButton(button: Button, primary: Boolean = false) {
        val radius = button.resources.displayMetrics.density * 14f
        val isPhone = button.resources.configuration.smallestScreenWidthDp < 600
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (primary) accent else elevatedSurface)
            setStroke((button.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                if (primary) Color.TRANSPARENT else Color.rgb(54, 67, 84))
        }
        val rippleColor = if (primary) Color.argb(45, 0, 0, 0) else Color.argb(50, 255, 255, 255)
        button.background = RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)
        button.setTextColor(if (primary) Color.rgb(8, 30, 29) else textPrimary)
        button.textSize = if (isPhone) 14f else 15f
        button.isAllCaps = false
        val density = button.resources.displayMetrics.density
        button.minHeight = ((if (isPhone) 44 else 48) * density).toInt()
        val horizontalPadding = ((if (isPhone) 10 else 14) * density).toInt()
        button.setPadding(horizontalPadding, 0, horizontalPadding, 0)
        button.elevation = 2f * button.resources.displayMetrics.density
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).setDuration(80).setInterpolator(AccelerateDecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            false
        }
    }

    fun styleSpinner(spinner: Spinner) {
        val isPhone = spinner.resources.configuration.smallestScreenWidthDp < 600
        val density = spinner.resources.displayMetrics.density
        spinner.minimumHeight = ((if (isPhone) 46 else 52) * density).toInt()
        spinner.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        spinner.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * spinner.resources.displayMetrics.density
            setColor(surface)
            setStroke((spinner.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(72, 88, 107))
        }
        spinner.setPopupBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * spinner.resources.displayMetrics.density
            setColor(elevatedSurface)
            setStroke((spinner.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(86, 105, 128))
        })
    }

    fun spinnerAdapter(context: Context, labels: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(
        context, android.R.layout.simple_spinner_item, labels
    ) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(textPrimary)
                setPadding((12 * resources.displayMetrics.density).toInt(), paddingTop,
                    (12 * resources.displayMetrics.density).toInt(), paddingBottom)
            }

        override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(textPrimary)
                setBackgroundColor(elevatedSurface)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setPadding((16 * resources.displayMetrics.density).toInt(), paddingTop,
                    (16 * resources.displayMetrics.density).toInt(), paddingBottom)
            }
    }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    fun styleSlider(slider: SeekBar) {
        slider.progressTintList = ColorStateList.valueOf(accent)
        slider.thumbTintList = ColorStateList.valueOf(accent)
        slider.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(65, 77, 94))
        slider.minimumHeight = (48 * slider.resources.displayMetrics.density).toInt()
        slider.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleY(1.12f).setDuration(90).setInterpolator(AccelerateDecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleY(1f).setDuration(120).setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            false
        }
    }

    fun cardBackground(view: View, radiusDp: Float = 18f) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * view.resources.displayMetrics.density
            setColor(surface)
        }
    }
}
