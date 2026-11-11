package com.remotegamestreamer.client

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** Controller artwork with a touch-selectable cylindrical Xbox input wheel. */
class ControllerMappingPreview(
    context: Context,
    private val inputs: List<String>,
    private var selectedInput: String?,
    private val onInputSelected: (String?) -> Unit,
) : View(context) {
    private data class Callout(val input: String, val label: String, val x: Float, val y: Float)

    private val bitmap: Bitmap? = runCatching {
        context.assets.open("images/xbox_controller_mapping.png").use(BitmapFactory::decodeStream)
    }.getOrNull()
    private val topBitmap: Bitmap? = runCatching {
        context.assets.open("images/xbox_controller_top_view.png").use(BitmapFactory::decodeStream)
    }.getOrNull()
    private val density = resources.displayMetrics.density
    private val calls = listOf(
        Callout("Left trigger", "LT  Left trigger", .24f, .095f),
        Callout("Left bumper", "LB  Left bumper", .245f, .135f),
        Callout("Left stick", "Left stick", .239f, .337f),
        Callout("View", "View", .433f, .35f),
        Callout("D-pad Up", "D-pad  ↑", .367f, .52f),
        Callout("D-pad Left", "D-pad  ←", .315f, .58f),
        Callout("D-pad Right", "D-pad  →", .418f, .58f),
        Callout("D-pad Down", "D-pad  ↓", .367f, .64f),
        Callout("Right trigger", "RT  Right trigger", .76f, .095f),
        Callout("Right bumper", "RB  Right bumper", .755f, .135f),
        Callout("Guide", "Xbox Guide", .50f, .235f),
        Callout("Y", "Y  Button", .765f, .24f),
        Callout("X", "X  Button", .695f, .335f),
        Callout("Menu", "Menu", .568f, .35f),
        Callout("B", "B  Button", .832f, .345f),
        Callout("A", "A  Button", .765f, .45f),
        Callout("Right stick", "Right stick", .63f, .56f),
    )
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density; color = Color.WHITE }
    private val iconBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(74, 224, 105)
    }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(184, 34, 43, 53) }
    private val selectedCard = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(204, 29, 91, 62) }
    private val cardOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(125, 132, 158, 178)
    }
    private val selectedOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(220, 74, 224, 105)
    }
    private val wheelBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(142, 103, 153, 160)
    }
    private val wheelItem = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 238, 246)
        textSize = 12f * density
    }
    private val controllerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val topControllerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val imageBounds = RectF()
    private val rowRects = HashMap<String, RectF>()
    private var dragLastY = 0f
    private var dragRemainder = 0f
    private var dragMoved = false
    private var wheelPhase = 0f
    private var wheelAnimator: ValueAnimator? = null
    private var pillAnimator: ValueAnimator? = null
    private var pillExpansion = 1f
    private var highlightAnimator: ValueAnimator? = null
    private var highlightX = calls.firstOrNull { it.input == selectedInput }?.x ?: .63f
    private var highlightY = calls.firstOrNull { it.input == selectedInput }?.y ?: .56f
    private var topHighlightX = topControllerPoint(selectedInput.orEmpty())?.first ?: .245f
    private var topHighlightY = topControllerPoint(selectedInput.orEmpty())?.second ?: .218f
    private var viewModeAnimator: ValueAnimator? = null
    private var topViewProgress = 0f
    private var topViewTarget = false

    fun selectInput(input: String) {
        if (input != selectedInput) {
            animateHighlight(input)
            animatePill()
        }
        selectedInput = input
        animateControllerView(input)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rowRects.clear()
        val imageArea = RectF(width * .015f, height * .19f, width * .66f, height * .83f)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(5, 17, 25), Color.rgb(9, 39, 48), Color.rgb(8, 24, 34)),
                floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        backgroundPaint.shader = null
        val ambientGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(width * .40f, height * .48f, width * .52f,
                intArrayOf(Color.argb(48, 24, 148, 116), Color.argb(18, 27, 110, 114), Color.TRANSPARENT),
                floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ambientGlow)
        ambientGlow.shader = null
        val geometry = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(13, 91, 202, 193) }
        val sweep = android.graphics.Path().apply {
            moveTo(width * .44f, 0f)
            lineTo(width * .74f, 0f)
            lineTo(width * .39f, height.toFloat())
            lineTo(width * .13f, height.toFloat())
            close()
        }
        canvas.drawPath(sweep, geometry)
        val imageGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(imageArea.centerX(), imageArea.centerY(), imageArea.width() * .61f,
                intArrayOf(Color.argb(34, 54, 222, 153), Color.argb(15, 36, 151, 143), Color.TRANSPARENT),
                floatArrayOf(0f, .57f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), imageGlow)
        imageGlow.shader = null
        bitmap?.let { source ->
            val scale = min(imageArea.width() / source.width, imageArea.height() / source.height)
            val w = source.width * scale
            val h = source.height * scale
            imageBounds.set(imageArea.centerX() - w / 2, imageArea.centerY() - h / 2,
                imageArea.centerX() + w / 2, imageArea.centerY() + h / 2)
            controllerPaint.alpha = ((1f - topViewProgress) * 255).toInt()
            canvas.drawBitmap(source, null, imageBounds, controllerPaint)
        }
        if (topViewProgress < 1f) drawSelectedHighlight(canvas, 1f - topViewProgress)
        if (topViewProgress > 0f) drawTopControllerView(canvas, topViewProgress)

        drawWheel(canvas)
    }

    private fun drawWheel(canvas: Canvas) {
        if (calls.isEmpty()) return
        val panel = RectF(width * .68f, height * .045f, width * .99f, height * .955f)
        wheelBody.shader = LinearGradient(panel.left, 0f, panel.right, 0f,
            intArrayOf(Color.rgb(10, 27, 35), Color.rgb(20, 48, 56), Color.rgb(9, 24, 32)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(panel, 24f * density, 24f * density, wheelBody)
        wheelBody.shader = null
        canvas.drawRoundRect(panel, 24f * density, 24f * density, wheelEdge)
        wheelLabel.textSize = 9f * density
        wheelLabel.color = Color.rgb(150, 173, 194)
        canvas.drawText("XBOX INPUTS", panel.left + 12f * density, panel.top + 18f * density, wheelLabel)
        wheelLabel.textSize = 8f * density
        wheelLabel.color = Color.rgb(123, 144, 165)
        val hint = "DRAG TO ROTATE"
        canvas.drawText(hint, panel.left + 12f * density, panel.bottom - 9f * density, wheelLabel)
        val selectedIndex = calls.indexOfFirst { it.input == selectedInput }.coerceAtLeast(0)
        val centerY = panel.centerY()
        val step = panel.height() * .115f
        val wheelContent = RectF(panel.left, panel.top + 28f * density, panel.right, panel.bottom - 24f * density)
        val clipSave = canvas.save()
        canvas.clipRect(wheelContent)
        for (offset in -3..3) {
            val item = calls[(selectedIndex + offset + calls.size) % calls.size]
            val depth = kotlin.math.abs(offset)
            val selected = selectedInput != null && offset == 0
            val depthScale = 1f - depth * .095f
            val scale = depthScale * if (selected) (1f + .085f * pillExpansion) else 1f
            val centerX = panel.centerX() + kotlin.math.sin(offset * .43f) * panel.width() * .055f
            val cy = centerY + (offset + wheelPhase) * step
            val itemWidth = panel.width() * .82f * scale
            val itemHeight = step * (if (selected) .86f + .055f * pillExpansion else .82f) * depthScale
            val rect = RectF(centerX - itemWidth / 2f, cy - itemHeight / 2f,
                centerX + itemWidth / 2f, cy + itemHeight / 2f)
            val visibleRect = RectF(rect)
            if (visibleRect.intersect(wheelContent)) rowRects[item.input] = visibleRect
            val alpha = (220 - depth * 38).coerceAtLeast(90)
            if (selected) {
                val glowAlpha = (72 + 42 * pillExpansion).toInt().coerceIn(0, 255)
                wheelGlow.shader = RadialGradient(rect.centerX(), cy, itemWidth * .68f,
                    intArrayOf(Color.argb(glowAlpha, 74, 224, 105), Color.argb(glowAlpha / 3, 74, 224, 105), Color.TRANSPARENT),
                    floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(rect.left - itemWidth * .08f, rect.top - itemHeight * .22f,
                    rect.right + itemWidth * .08f, rect.bottom + itemHeight * .22f), itemHeight, itemHeight, wheelGlow)
                wheelGlow.shader = null
            }
            wheelItem.color = if (selected) Color.argb(245, 17, 91, 55) else Color.argb(alpha, 31, 54, 62)
            canvas.drawRoundRect(rect, itemHeight * .5f, itemHeight * .5f, wheelItem)
            wheelEdge.color = if (selected) Color.argb(245, 74, 224, 146) else Color.argb(alpha / 2, 103, 153, 160)
            canvas.drawRoundRect(rect, itemHeight * .5f, itemHeight * .5f, wheelEdge)
            val iconX = rect.left + 16f * density * scale
            iconBadge.color = if (selected) Color.argb(100, 43, 190, 75) else Color.argb(alpha / 4, 156, 183, 207)
            canvas.drawCircle(iconX, cy, 8f * density * scale, iconBadge)
            val iconColor = if (selected) Color.rgb(159, 255, 218) else Color.rgb(183, 210, 228)
            drawInputIcon(canvas, item.input, iconX, cy, iconColor)
            wheelLabel.color = if (selected) Color.WHITE else Color.argb(alpha, 225, 233, 240)
            wheelLabel.textSize = (if (selected) 11.5f else 10.5f) * density * scale
            val label = item.label
            val baseline = cy - (wheelLabel.ascent() + wheelLabel.descent()) / 2f
            canvas.drawText(label, rect.left + 29f * density * scale, baseline, wheelLabel)
        }
        canvas.restoreToCount(clipSave)
    }

    private fun drawSelectedHighlight(canvas: Canvas, opacity: Float) {
        val selected = calls.firstOrNull { it.input == selectedInput } ?: return
        val cx = imageBounds.left + imageBounds.width() * highlightX
        val cy = imageBounds.top + imageBounds.height() * highlightY
        val scale = min(imageBounds.width(), imageBounds.height())
        val radius = when {
            selected.input.contains("stick", ignoreCase = true) -> scale * .09f
            selected.input.contains("D-pad", ignoreCase = true) -> scale * .052f
            selected.input in setOf("Y", "X", "B", "A") -> scale * .047f
            selected.input == "Left trigger" || selected.input == "Right trigger" -> scale * .052f
            selected.input == "Left bumper" || selected.input == "Right bumper" -> scale * .055f
            else -> scale * .038f
        }
        focusFill.shader = RadialGradient(cx, cy, radius * 1.9f,
            intArrayOf(Color.argb(115, 74, 224, 105), Color.argb(50, 74, 224, 105), Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        focusFill.alpha = (255 * opacity).toInt()
        canvas.drawCircle(cx, cy, radius * 1.9f, focusFill)
        focusFill.shader = null
        focusRing.alpha = (220 * opacity).toInt()
        val shoulder = selected.input.contains("trigger", ignoreCase = true) || selected.input.contains("bumper", ignoreCase = true)
        val rect = when (selected.input) {
            "Left bumper", "Right bumper" -> RectF(cx - imageBounds.width() * .105f, cy - imageBounds.height() * .058f,
                cx + imageBounds.width() * .105f, cy + imageBounds.height() * .058f)
            "Left trigger", "Right trigger" -> RectF(cx - imageBounds.width() * .061f, cy - imageBounds.height() * .053f,
                cx + imageBounds.width() * .061f, cy + imageBounds.height() * .053f)
            else -> RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        }
        if (shoulder) {
            canvas.drawRoundRect(rect, radius * .5f, radius * .5f, focusRing)
        } else {
            canvas.drawCircle(cx, cy, radius, focusRing)
        }
    }

    /** Fades to a shoulder-edge view when a bumper or trigger is selected. */
    private fun drawTopControllerView(canvas: Canvas, opacity: Float) {
        val source = topBitmap ?: return
        val save = canvas.save()
        canvas.scale(1f, .88f + .12f * opacity, imageBounds.centerX(), imageBounds.centerY())
        topControllerPaint.alpha = (255 * opacity).toInt()
        canvas.drawBitmap(source, null, imageBounds, topControllerPaint)
        val selectedPoint = topControllerPoint(selectedInput.orEmpty())
        if (selectedPoint != null) {
            val cx = imageBounds.left + imageBounds.width() * topHighlightX
            val cy = imageBounds.top + imageBounds.height() * topHighlightY
            val isBumper = selectedInput?.contains("bumper", ignoreCase = true) == true
            val rx = imageBounds.width() * if (isBumper) .108f else .072f
            val ry = imageBounds.height() * if (isBumper) .058f else .092f
            focusFill.alpha = (255 * opacity).toInt()
            focusFill.shader = RadialGradient(cx, cy, rx * 1.6f,
                intArrayOf(Color.argb(140, 74, 224, 105), Color.argb(45, 74, 224, 105), Color.TRANSPARENT),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, rx * 1.6f, focusFill)
            focusFill.shader = null
            focusRing.alpha = (235 * opacity).toInt()
            canvas.drawRoundRect(RectF(cx - rx, cy - ry, cx + rx, cy + ry), ry * .45f, ry * .45f, focusRing)
        }
        canvas.restoreToCount(save)
    }

    private fun animateControllerView(input: String) {
        val wantsTopView = input == "Left trigger" || input == "Left bumper" ||
            input == "Right trigger" || input == "Right bumper"
        if (wantsTopView == topViewTarget) return
        topViewTarget = wantsTopView
        viewModeAnimator?.cancel()
        val target = if (wantsTopView) 1f else 0f
        viewModeAnimator = ValueAnimator.ofFloat(topViewProgress, target).apply {
            duration = 500L
            interpolator = DecelerateInterpolator(1.45f)
            addUpdateListener { topViewProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawInputIcon(canvas: Canvas, input: String, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val r = 5f * density
        when {
            input in setOf("Y", "X", "B", "A") -> {
                canvas.drawCircle(cx, cy, r, iconPaint)
                text.textSize = 8f * density
                text.color = color
                val baseline = cy - (text.ascent() + text.descent()) / 2f
                canvas.drawText(input, cx - text.measureText(input) / 2f, baseline, text)
                text.textSize = 12f * density
            }
            input.contains("stick", ignoreCase = true) || input == "L3" || input == "R3" -> {
                canvas.drawCircle(cx, cy, r, iconPaint)
                canvas.drawCircle(cx, cy, 1.6f * density, iconPaint)
            }
            input.contains("D-pad", ignoreCase = true) -> {
                canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 1.6f * density, 1.6f * density, iconPaint)
                val direction = when {
                    input.endsWith("Up") -> 0
                    input.endsWith("Right") -> 1
                    input.endsWith("Down") -> 2
                    else -> 3
                }
                val (dx, dy) = when (direction) {
                    0 -> 0f to -1f
                    1 -> 1f to 0f
                    2 -> 0f to 1f
                    else -> -1f to 0f
                }
                canvas.drawLine(cx - dx * 2.5f * density, cy - dy * 2.5f * density,
                    cx + dx * 2.5f * density, cy + dy * 2.5f * density, iconPaint)
            }
            input.contains("trigger", ignoreCase = true) || input.contains("bumper", ignoreCase = true) -> {
                canvas.drawRoundRect(RectF(cx - 6f * density, cy - 3.5f * density,
                    cx + 6f * density, cy + 3.5f * density), 3f * density, 3f * density, iconPaint)
                canvas.drawLine(cx, cy - 2f * density, cx, cy + 2f * density, iconPaint)
            }
            else -> {
                canvas.drawRoundRect(RectF(cx - 5f * density, cy - 4f * density,
                    cx + 5f * density, cy + 4f * density), 2f * density, 2f * density, iconPaint)
                canvas.drawCircle(cx - 2f * density, cy, .7f * density, iconPaint)
                canvas.drawCircle(cx + 2f * density, cy, .7f * density, iconPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragLastY = event.y
                dragRemainder = 0f
                dragMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val panelHeight = height * .91f
                val delta = event.y - dragLastY
                if (kotlin.math.abs(delta) > 3f * density) dragMoved = true
                dragLastY = event.y
                dragRemainder += delta
                val step = (panelHeight * .115f).coerceAtLeast(1f)
                while (dragRemainder >= step) { rotateSelection(-1); dragRemainder -= step }
                while (dragRemainder <= -step) { rotateSelection(1); dragRemainder += step }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wheelInput = rowRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                val input = wheelInput ?: findControllerInput(event.x, event.y)
                if (input != null && input in inputs) select(input)
                else if (!dragMoved) clearSelection()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun rotateSelection(delta: Int) {
        if (calls.isEmpty()) return
        val index = calls.indexOfFirst { it.input == selectedInput }.coerceAtLeast(0)
        select(calls[(index + delta + calls.size) % calls.size].input)
    }

    private fun findControllerInput(x: Float, y: Float): String? {
        if (!imageBounds.contains(x, y) || imageBounds.isEmpty) return null
        val unit = min(imageBounds.width(), imageBounds.height())
        if (topViewProgress >= .5f) {
            val topInputs = listOf(
                "Left bumper" to (.245f to .218f), "Right bumper" to (.755f to .218f),
                "Left trigger" to (.245f to .10f), "Right trigger" to (.755f to .10f),
            )
            return topInputs.mapNotNull { (input, point) ->
                val dx = (x - (imageBounds.left + imageBounds.width() * point.first)) / unit
                val dy = (y - (imageBounds.top + imageBounds.height() * point.second)) / unit
                val radius = if (input.contains("bumper")) .12f else .09f
                input to ((dx * dx + dy * dy) / (radius * radius))
            }.filter { it.second <= 1f }.minByOrNull { it.second }?.first
        }
        return calls.asSequence().map { item ->
            val cx = imageBounds.left + imageBounds.width() * item.x
            val cy = imageBounds.top + imageBounds.height() * item.y
            val dx = (x - cx) / unit
            val dy = (y - cy) / unit
            val hitRadius = when {
                item.input.contains("trigger", ignoreCase = true) || item.input.contains("bumper", ignoreCase = true) -> .060f
                item.input.contains("stick", ignoreCase = true) || item.input == "L3" || item.input == "R3" -> .070f
                item.input.contains("D-pad", ignoreCase = true) -> .035f
                item.input in setOf("A", "B", "X", "Y") -> .036f
                else -> .045f
            }
            item to (dx * dx + dy * dy) / (hitRadius * hitRadius)
        }.filter { it.second <= 1f }
            .minByOrNull { it.second }
            ?.first?.input
    }

    private fun select(input: String) {
        val oldIndex = calls.indexOfFirst { it.input == selectedInput }
        val newIndex = calls.indexOfFirst { it.input == input }
        if (newIndex < 0) return
        val changed = input != selectedInput
        if (changed) playSoundEffect(SoundEffectConstants.CLICK)
        if (changed) {
            animateHighlight(input)
            animateControllerView(input)
        }
        var phase = if (oldIndex < 0) 0 else newIndex - oldIndex
        if (phase > calls.size / 2) phase -= calls.size
        if (phase < -calls.size / 2) phase += calls.size
        selectedInput = input
        onInputSelected(input)
        wheelAnimator?.cancel()
        pillAnimator?.cancel()
        pillExpansion = .25f
        pillAnimator = ValueAnimator.ofFloat(.25f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pillExpansion = it.animatedValue as Float; invalidate() }
            start()
        }
        if (phase != 0) {
            wheelPhase = (wheelPhase + phase.toFloat()).coerceIn(-.9f, .9f)
            wheelAnimator = ValueAnimator.ofFloat(wheelPhase, 0f).apply {
                duration = 275L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { wheelPhase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            wheelPhase = 0f
        }
        invalidate()
    }

    private fun clearSelection() {
        if (selectedInput == null) return
        selectedInput = null
        animateControllerView("")
        wheelAnimator?.cancel()
        wheelPhase = 0f
        pillAnimator?.cancel()
        pillExpansion = 1f
        onInputSelected(null)
        invalidate()
    }

    private fun animatePill() {
        pillAnimator?.cancel()
        pillExpansion = .25f
        pillAnimator = ValueAnimator.ofFloat(pillExpansion, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pillExpansion = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateHighlight(input: String) {
        val target = calls.firstOrNull { it.input == input } ?: return
        val topTarget = topControllerPoint(input)
        val startX = highlightX
        val startY = highlightY
        val startTopX = topHighlightX
        val startTopY = topHighlightY
        highlightAnimator?.cancel()
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                highlightX = startX + (target.x - startX) * t
                highlightY = startY + (target.y - startY) * t
                if (topTarget != null) {
                    topHighlightX = startTopX + (topTarget.first - startTopX) * t
                    topHighlightY = startTopY + (topTarget.second - startTopY) * t
                }
                invalidate()
            }
            start()
        }
    }

    private fun topControllerPoint(input: String): Pair<Float, Float>? = when (input) {
        "Left bumper" -> .245f to .218f
        "Right bumper" -> .755f to .218f
        "Left trigger" -> .245f to .10f
        "Right trigger" -> .755f to .10f
        else -> null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
