package com.remotegamestreamer.client.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import java.util.HashMap

/**
 * Translucent HUD built from [HudLayout]. In play it produces controller state. In
 * [editMode] touches drag controls around instead, for the layout editor.
 */
class TouchHudOverlay(
    context: Context,
    private val layout: HudLayout,
    opacityPercent: Int,
    private val stickLocked: Boolean,
    cameraSensitivityXPercent: Int,
    cameraSensitivityYPercent: Int,
    private val editMode: Boolean = false,
) : View(context) {

    private class Control(val spec: ControlSpec, val place: Placement) {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        var pressCount = 0
    }

    private enum class Target { STICK, CAMERA, PAD, BUTTON }

    private class Track(val target: Target, val button: Control?, val downX: Float, val downY: Float, val downTime: Long) {
        var lastX = downX
        var lastY = downY
        var moved = false
    }

    private val density = resources.displayMetrics.density
    private val controls = HudCatalog.controls.map { Control(it, layout.placement(it.id)) }
    private val stick = controls.first { it.spec.kind == ControlKind.STICK }
    private val buttons = controls.filter { it !== stick }

    private val alphaScale = opacityPercent / 50f
    private var cameraGainX = BASE_CAMERA_GAIN * cameraSensitivityXPercent / 100f
    private var cameraGainY = BASE_CAMERA_GAIN * cameraSensitivityYPercent / 100f

    private val tracks = HashMap<Int, Track>()
    private val stickOut = ShortArray(2)

    private var stickCenterX = 0f
    private var stickCenterY = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var stickActive = false

    private var touchpadMode = false
    private var moreExpanded = false
    private var padPeakPointers = 0
    private var padDragged = false

    private var selected: Control? = null
    private var grabDx = 0f
    private var grabDy = 0f

    /** Editor only: told the selected control's id, or null when nothing is selected. */
    var onSelectionChanged: ((String?) -> Unit)? = null
    val selectedControlId: String? get() = selected?.spec?.id

    /** Called when the player lifts their finger off the Kbd button. */
    var onKeyboardRequested: (() -> Unit)? = null

    /** Called when the player lifts their finger off the Sens button. */
    var onCameraSensitivityRequested: (() -> Unit)? = null

    /** Applies to the next camera movement. Call on the UI thread. */
    fun setCameraSensitivity(xPercent: Int, yPercent: Int) {
        cameraGainX = BASE_CAMERA_GAIN * xPercent / 100f
        cameraGainY = BASE_CAMERA_GAIN * yPercent / 100f
    }

    /** Set from the host's Welcome. Without it the Pad and Kbd buttons are hidden, because the host would ignore them. */
    var touchpadAvailable = false
        set(value) {
            field = value
            if (!value) touchpadMode = false
            invalidate()
        }

    // Shared with the sender thread, guarded by "this".
    private var buttonsMask = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var thumbLX: Short = 0
    private var thumbLY: Short = 0
    private val cameraMotion = CameraMotion()
    private val cameraOut = ShortArray(2)
    private var lastSampleNanos = 0L
    private var mouseX = 0f
    private var mouseY = 0f
    private var leftClickUntil = 0L
    private var rightClickUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(alpha(150), 255, 255, 255)
    }
    private val selectionRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(255, 214, 0)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha(210), 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val customImages = HashMap<String, Bitmap?>()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageBounds = RectF()
    private val imageClip = Path()

    private fun alpha(base: Int) = (base * alphaScale).toInt().coerceIn(0, 255)

    /** Called by the sender thread at 200 Hz. */
    fun sample(out: ControllerState) {
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            val nanos = System.nanoTime()
            cameraMotion.step((nanos - lastSampleNanos) / 1_000_000f, cameraOut)
            lastSampleNanos = nanos

            out.buttons = buttonsMask
            out.leftTrigger = leftTrigger
            out.rightTrigger = rightTrigger
            out.thumbLX = thumbLX
            out.thumbLY = thumbLY
            out.thumbRX = cameraOut[0]
            out.thumbRY = cameraOut[1]

            val dx = mouseX.toInt()
            val dy = mouseY.toInt()
            mouseX -= dx
            mouseY -= dy
            out.mouseDeltaX = InputNormalizer.clampToShort(dx)
            out.mouseDeltaY = InputNormalizer.clampToShort(dy)

            var mouse = 0
            if (now < leftClickUntil) mouse = mouse or ControllerState.MOUSE_LEFT
            if (now < rightClickUntil) mouse = mouse or ControllerState.MOUSE_RIGHT
            out.mouseButtons = mouse
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = placeControls()

    private fun placeControls() {
        for (c in controls) place(c)
    }

    private fun place(c: Control) {
        c.cx = c.place.fx * width
        c.cy = c.place.fy * height
        c.radius = c.spec.radiusDp * c.place.scale * density
    }

    private fun isShown(c: Control) = editMode || when {
        c.spec.kind == ControlKind.GROUP_TOGGLE -> true
        c.place.inMore -> moreExpanded && (touchpadAvailable || (c.spec.kind != ControlKind.TOUCHPAD_TOGGLE && c.spec.kind != ControlKind.KEYBOARD))
        else -> touchpadAvailable || (c.spec.kind != ControlKind.TOUCHPAD_TOGGLE && c.spec.kind != ControlKind.KEYBOARD)
    }

    private fun isUsable(c: Control) = isShown(c) && c.place.visible

    private fun buttonAt(x: Float, y: Float): Control? = buttons
        .filter { isUsable(it) && hypot(x - it.cx, y - it.cy) <= it.radius * HIT_SLOP }
        .minByOrNull { hypot(x - it.cx, y - it.cy) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return onEditTouch(e)

        // Otherwise Android holds touches back until the next screen refresh.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) pointerMove(e, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e.getPointerId(e.actionIndex), e.eventTime, cancelled = false)
            MotionEvent.ACTION_CANCEL -> for (id in tracks.keys.toList()) pointerUp(id, e.eventTime, cancelled = true)
        }
        invalidate()
        return true
    }

    private fun startsStick(x: Float, y: Float): Boolean = when {
        stickActive -> false
        stickLocked -> hypot(x - stick.cx, y - stick.cy) <= stick.radius * STICK_GRAB
        else -> x < width * STICK_ZONE
    }

    private fun pointerDown(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        val id = e.getPointerId(index)

        val hit = buttonAt(x, y)
        val target = when {
            hit != null -> Target.BUTTON
            touchpadMode -> Target.PAD
            startsStick(x, y) -> Target.STICK
            else -> Target.CAMERA
        }
        tracks[id] = Track(target, hit, x, y, e.eventTime)

        when (target) {
            Target.BUTTON -> press(hit!!)
            Target.STICK -> {
                stickActive = true
                stickCenterX = if (stickLocked) stick.cx else x
                stickCenterY = if (stickLocked) stick.cy else y
                updateStick(x, y)
            }
            Target.PAD -> {
                if (padPointers() == 1) {
                    padPeakPointers = 0
                    padDragged = false
                }
                padPeakPointers = maxOf(padPeakPointers, padPointers())
            }
            Target.CAMERA -> Unit
        }
    }

    private fun updateStick(x: Float, y: Float) {
        thumbX = x
        thumbY = y
        InputNormalizer.stick(x - stickCenterX, y - stickCenterY, stick.radius, stickOut)
        synchronized(this) {
            thumbLX = stickOut[0]
            thumbLY = stickOut[1]
        }
    }

    private fun pointerMove(e: MotionEvent, index: Int) {
        val track = tracks[e.getPointerId(index)] ?: return
        val x = e.getX(index)
        val y = e.getY(index)

        when (track.target) {
            Target.STICK -> updateStick(x, y)

            Target.CAMERA -> {
                val dx = (x - track.lastX) / density
                val dy = (y - track.lastY) / density
                if (dx != 0f || dy != 0f) {
                    synchronized(this) { cameraMotion.add(dx, -dy, cameraGainX, cameraGainY) }
                }
            }

            Target.PAD -> {
                val dx = x - track.lastX
                val dy = y - track.lastY
                if (abs(x - track.downX) > TAP_SLOP_DP * density || abs(y - track.downY) > TAP_SLOP_DP * density) {
                    track.moved = true
                    padDragged = true
                }
                if (padPointers() == 1) {
                    synchronized(this) {
                        mouseX += dx / density * MOUSE_SENSITIVITY
                        mouseY += dy / density * MOUSE_SENSITIVITY
                    }
                }
            }

            Target.BUTTON -> Unit
        }

        track.lastX = x
        track.lastY = y
    }

    private fun pointerUp(id: Int, eventTime: Long, cancelled: Boolean) {
        val track = tracks.remove(id) ?: return

        when (track.target) {
            Target.BUTTON -> {
                release(track.button!!)
                if (!cancelled) {
                    when (track.button.spec.kind) {
                        ControlKind.KEYBOARD -> onKeyboardRequested?.invoke()
                        ControlKind.CAMERA_SENSITIVITY -> onCameraSensitivityRequested?.invoke()
                        else -> Unit
                    }
                }
            }
            Target.STICK -> {
                stickActive = false
                synchronized(this) {
                    thumbLX = 0
                    thumbLY = 0
                }
            }
            Target.PAD -> {
                val quick = eventTime - track.downTime <= TAP_MAX_MS
                if (padPointers() == 0 && !padDragged && quick && !cancelled) {
                    val until = eventTime + CLICK_HOLD_MS
                    synchronized(this) {
                        if (padPeakPointers >= 2) rightClickUntil = until else leftClickUntil = until
                    }
                }
            }
            Target.CAMERA -> Unit
        }
    }

    private fun padPointers() = tracks.values.count { it.target == Target.PAD }

    private fun press(b: Control) {
        b.pressCount++
        when (b.spec.kind) {
            ControlKind.GROUP_TOGGLE -> { moreExpanded = !moreExpanded; invalidate() }
            ControlKind.TOUCHPAD_TOGGLE -> touchpadMode = !touchpadMode
            ControlKind.LEFT_TRIGGER -> synchronized(this) { leftTrigger = 255 }
            ControlKind.RIGHT_TRIGGER -> synchronized(this) { rightTrigger = 255 }
            ControlKind.BIT -> synchronized(this) { buttonsMask = buttonsMask or b.spec.bit }
            ControlKind.KEYBOARD, ControlKind.CAMERA_SENSITIVITY, ControlKind.STICK -> Unit
        }
    }

    private fun release(b: Control) {
        b.pressCount--
        if (b.pressCount > 0) return
        when (b.spec.kind) {
            ControlKind.GROUP_TOGGLE -> Unit
            ControlKind.LEFT_TRIGGER -> synchronized(this) { leftTrigger = 0 }
            ControlKind.RIGHT_TRIGGER -> synchronized(this) { rightTrigger = 0 }
            ControlKind.BIT -> synchronized(this) { buttonsMask = buttonsMask and b.spec.bit.inv() }
            ControlKind.TOUCHPAD_TOGGLE, ControlKind.KEYBOARD, ControlKind.CAMERA_SENSITIVITY, ControlKind.STICK -> Unit
        }
    }

    // Layout editor

    private fun onEditTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = controls
                    .filter { isShown(it) && hypot(x - it.cx, y - it.cy) <= maxOf(it.radius, MIN_EDIT_GRAB_DP * density) }
                    .minByOrNull { hypot(x - it.cx, y - it.cy) }
                selected = c
                if (c != null) {
                    grabDx = x - c.cx
                    grabDy = y - c.cy
                }
                onSelectionChanged?.invoke(c?.spec?.id)
            }
            MotionEvent.ACTION_MOVE -> selected?.let {
                layout.move(it.spec.id, (x - grabDx) / width, (y - grabDy) / height)
                place(it)
            }
        }
        invalidate()
        return true
    }

    fun resizeSelected(delta: Float) {
        val c = selected ?: return
        layout.resize(c.spec.id, delta)
        place(c)
        invalidate()
    }

    fun toggleSelectedVisible() {
        val c = selected ?: return
        layout.toggleVisible(c.spec.id)
        onSelectionChanged?.invoke(c.spec.id)
        invalidate()
    }

    fun resetLayout() {
        layout.reset()
        selected = null
        placeControls()
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    fun setSelectedImage(uri: String?) {
        val control = selected ?: return
        if (control.spec.kind == ControlKind.STICK) return
        control.place.imageUri = uri
        invalidate()
    }

    fun toggleSelectedGroup() {
        val control = selected ?: return
        if (control.spec.id == "more" || control.spec.kind == ControlKind.STICK) return
        control.place.inMore = !control.place.inMore
        placeControls()
        invalidate()
    }

    fun applyPreset(preset: Int) {
        layout.applyPreset(preset)
        selected = null
        placeControls()
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (b in buttons) {
            if (!isShown(b) || (!editMode && !b.place.visible)) continue

            val active = b.pressCount > 0 || (b.spec.kind == ControlKind.TOUCHPAD_TOGGLE && touchpadMode)
            fill.color = Color.argb(alpha(if (active) 150 else 50), 255, 255, 255)
            val hiddenInEditor = !b.place.visible
            if (hiddenInEditor) fill.color = Color.argb(20, 255, 80, 80)
            canvas.drawCircle(b.cx, b.cy, b.radius, fill)
            val image = b.place.imageUri?.let(::loadCustomImage)
            if (image != null) {
                imageBounds.set(b.cx - b.radius, b.cy - b.radius, b.cx + b.radius, b.cy + b.radius)
                imageClip.reset()
                imageClip.addCircle(b.cx, b.cy, b.radius, Path.Direction.CW)
                val checkpoint = canvas.save()
                canvas.clipPath(imageClip)
                imagePaint.alpha = alpha(255)
                canvas.drawBitmap(image, null, imageBounds, imagePaint)
                canvas.restoreToCount(checkpoint)
            }
            canvas.drawCircle(b.cx, b.cy, b.radius, if (b === selected) selectionRing else ring)
            val label = if (b.spec.kind == ControlKind.GROUP_TOGGLE && moreExpanded) "More ▴" else b.spec.label
            canvas.drawText(label, b.cx, b.cy - (text.ascent() + text.descent()) / 2f, text)
        }

        if (touchpadMode) {
            canvas.drawText("Touchpad: drag to move, tap to click, two-finger tap to right-click", width / 2f, height - 16f * density, text)
            return
        }

        val cx = if (stickActive) stickCenterX else stick.cx
        val cy = if (stickActive) stickCenterY else stick.cy
        fill.color = Color.argb(alpha(35), 255, 255, 255)
        canvas.drawCircle(cx, cy, stick.radius, fill)
        canvas.drawCircle(cx, cy, stick.radius, if (stick === selected) selectionRing else ring)

        var kx = 0f
        var ky = 0f
        if (stickActive) {
            kx = thumbX - stickCenterX
            ky = thumbY - stickCenterY
            val distance = hypot(kx, ky)
            if (distance > stick.radius) {
                kx *= stick.radius / distance
                ky *= stick.radius / distance
            }
        }
        fill.color = Color.argb(alpha(if (stickActive) 140 else 70), 255, 255, 255)
        canvas.drawCircle(cx + kx, cy + ky, stick.radius * 0.42f, fill)
    }

    private fun loadCustomImage(uriText: String): Bitmap? {
        if (customImages.containsKey(uriText)) return customImages[uriText]
        val bitmap = runCatching {
            val uri = Uri.parse(uriText)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (largest > MAX_CUSTOM_IMAGE_EDGE) (largest / MAX_CUSTOM_IMAGE_EDGE).coerceAtLeast(1) else 1
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
        customImages[uriText] = bitmap
        return bitmap
    }

    private companion object {
        const val STICK_ZONE = 0.45f
        const val STICK_GRAB = 1.25f
        const val HIT_SLOP = 1.15f
        const val MIN_EDIT_GRAB_DP = 28f
        const val BASE_CAMERA_GAIN = 0.75f
        const val TAP_SLOP_DP = 10f
        const val TAP_MAX_MS = 250L
        const val CLICK_HOLD_MS = 40L
        const val MOUSE_SENSITIVITY = 1.8f
        const val MAX_CUSTOM_IMAGE_EDGE = 512
    }
}
