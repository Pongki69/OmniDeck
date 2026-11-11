package com.remotegamestreamer.client.input

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import java.util.HashMap

enum class HudPreviewScene { CONTROLLER, GAME_SCREEN }

/**
 * Translucent HUD built from [HudLayout]. In play it produces controller state. In
 * [editMode] touches drag controls around instead, for the layout editor.
 */
class TouchHudOverlay(
    context: Context,
    private val layout: HudLayout,
    private val stickLocked: Boolean,
    cameraSensitivityXPercent: Int,
    cameraSensitivityYPercent: Int,
    editMode: Boolean = false,
    private val drawControllerPreview: Boolean = false,
) : View(context) {

    private var editModeEnabled = editMode

    private class Control(val spec: ControlSpec, val place: Placement) {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        var pressCount = 0
    }

    private enum class Target { STICK, RIGHT_STICK, CAMERA, PAD, BUTTON, IGNORE }

    private class Track(val target: Target, val button: Control?, val downX: Float, val downY: Float, val downTime: Long) {
        var lastX = downX
        var lastY = downY
        var moved = false
    }

    private val density = resources.displayMetrics.density
    private val controls = HudCatalog.controls.map { Control(it, layout.placement(it.id)) }
    private val stick = controls.first { it.spec.kind == ControlKind.STICK }
    private val rightStick = controls.first { it.spec.kind == ControlKind.RIGHT_STICK }
    private val buttons = controls.filter { it !== stick }

    private var cameraGainX = BASE_CAMERA_GAIN * cameraSensitivityXPercent / 100f
    private var cameraGainY = BASE_CAMERA_GAIN * cameraSensitivityYPercent / 100f
    @Volatile private var touchDragCameraEnabled = true
    @Volatile private var cameraActive = true
    private var lastPhysicalStickSampleNanos = 0L

    private val tracks = HashMap<Int, Track>()
    private val stickOut = ShortArray(2)
    private val rightStickOut = ShortArray(2)

    private var stickCenterX = 0f
    private var stickCenterY = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var stickActive = false
    private var rightStickCenterX = 0f
    private var rightStickCenterY = 0f
    private var rightThumbX = 0f
    private var rightThumbY = 0f
    private var rightStickActive = false

    private var touchpadMode = false
    private var expandedDrawerId: String? = if (editMode) "more" else null
    private var drawerOpen = editMode
    private var menuExpansion = if (editMode) 1f else 0f
    private var moreMenuAnimator: ValueAnimator? = null
    private var hudHidden = false
    @Volatile private var streamConnected = false
    private var hudToggleAlpha = 1f
    private var hudToggleAnimator: ValueAnimator? = null
    private val hideHudToggle = Runnable {
        if (streamConnected && !editModeEnabled) {
            hudToggleAnimator?.cancel()
            hudToggleAnimator = ValueAnimator.ofFloat(hudToggleAlpha, 0f).apply {
                duration = HUD_ICON_FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    hudToggleAlpha = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }
    private var previewScene = HudPreviewScene.CONTROLLER
    private var previewScreenScale = 100
    private var previewStretchToFill = false
    private var padPeakPointers = 0
    private var padDragged = false

    private var selected: Control? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var resizeGesture = false
    private var resizeStartScale = 1f
    private var resizeStartDistance = 1f
    private var screenResizeGesture = false
    private var screenResizeStartScale = 100
    private var screenResizeStartDistance = 1f

    /** Editor only: told the selected control's id, or null when nothing is selected. */
    var onSelectionChanged: ((String?) -> Unit)? = null
    val selectedControlId: String? get() = selected?.spec?.id

    /** Called when the player lifts their finger off the Kbd button. */
    var onKeyboardRequested: (() -> Unit)? = null

    /** Called when the player lifts their finger off the Sens button. */
    var onCameraSensitivityRequested: (() -> Unit)? = null

    /** Long-pressing the HUD button asks the host screen to show live layout tools. */
    var onLayoutEditRequested: (() -> Unit)? = null
    var onPreviewScreenScaleChanged: ((Int) -> Unit)? = null

    /** Applies to the next camera movement. Call on the UI thread. */
    fun setCameraSensitivity(xPercent: Int, yPercent: Int) {
        cameraGainX = BASE_CAMERA_GAIN * xPercent / 100f
        cameraGainY = BASE_CAMERA_GAIN * yPercent / 100f
    }

    fun setTouchDragCameraEnabled(enabled: Boolean) {
        touchDragCameraEnabled = enabled
        if (!enabled) synchronized(this) { cameraMotion.clear() }
        postInvalidateOnAnimation()
    }

    var onCameraActiveChanged: ((Boolean) -> Unit)? = null

    /** Adds ordinary analog stick deflection to the same relative-motion queue as touch swipes. */
    fun addPhysicalStickCamera(x: Float, y: Float) {
        if (!touchDragCameraEnabled || !cameraActive) return
        val now = System.nanoTime()
        val elapsed = if (lastPhysicalStickSampleNanos == 0L) 5f else
            ((now - lastPhysicalStickSampleNanos) / 1_000_000f).coerceIn(0f, 12f)
        lastPhysicalStickSampleNanos = now
        synchronized(this) { cameraMotion.addDeflection(x, y, elapsed) }
    }

    fun toggleCameraActive(): Boolean {
        cameraActive = !cameraActive
        if (!cameraActive) synchronized(this) { cameraMotion.clear() }
        onCameraActiveChanged?.invoke(cameraActive)
        postInvalidateOnAnimation()
        return cameraActive
    }

    fun setEditMode(enabled: Boolean) {
        editModeEnabled = enabled
        selected = null
        resizeGesture = false
        if (!enabled) {
            hudHidden = false
            expandedDrawerId = null
            drawerOpen = false
            menuExpansion = 0f
        } else {
            hudHidden = false
            expandedDrawerId = "more"
            drawerOpen = true
            menuExpansion = 1f
        }
        repositionDrawers()
        if (enabled) {
            removeCallbacks(hideHudToggle)
            hudToggleAnimator?.cancel()
            hudToggleAlpha = 1f
        } else if (streamConnected) {
            showHudToggleTemporarily()
        }
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    /** The one-second HUD icon fade is enabled only for a live host session. */
    fun setStreamConnected(connected: Boolean) {
        streamConnected = connected
        removeCallbacks(hideHudToggle)
        hudToggleAnimator?.cancel()
        hudToggleAlpha = 1f
        if (connected && !editModeEnabled) postDelayed(hideHudToggle, HUD_ICON_VISIBLE_MS)
        invalidate()
    }

    private fun showHudToggleTemporarily() {
        if (!streamConnected || editModeEnabled) return
        removeCallbacks(hideHudToggle)
        hudToggleAnimator?.cancel()
        hudToggleAlpha = 1f
        postInvalidateOnAnimation()
        postDelayed(hideHudToggle, HUD_ICON_VISIBLE_MS)
    }

    fun setPreviewScene(scene: HudPreviewScene) {
        previewScene = scene
        invalidate()
    }

    fun setPreviewScreenScale(percent: Int) {
        previewScreenScale = percent.coerceIn(50, 100)
        invalidate()
    }

    fun setPreviewStretchToFill(enabled: Boolean) {
        previewStretchToFill = enabled
        invalidate()
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
    private val removeHandleIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha(210), 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val customImages = HashMap<String, Bitmap?>()
    private val customIcons = HashMap<String, SvgIconParser.Icon?>()
    private val controllerModelBitmap: Bitmap? = runCatching {
        context.assets.open("images/xbox_controller_mapping.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val imageBounds = RectF()
    private val controllerModelBounds = RectF()
    private val imageClip = Path()
    private val controllerBody = Path()
    private val controllerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 135, 155, 182); style = Paint.Style.FILL }
    private val controllerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 194, 209, 231); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val controllerDetail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 210, 222, 241); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private var controllerGradientWidth = -1
    private var controllerGradientHeight = -1

    private fun alpha(base: Int, opacity: Int = 100) = (base * opacity / 100f).toInt().coerceIn(0, 255)

    /** Called by the sender thread at 200 Hz. */
    fun sample(out: ControllerState) {
        val now = SystemClock.uptimeMillis()
        synchronized(this) {
            val nanos = System.nanoTime()
            if (touchDragCameraEnabled && cameraActive) cameraMotion.step((nanos - lastSampleNanos) / 1_000_000f, cameraOut)
            else { cameraMotion.clear(); cameraOut[0] = 0; cameraOut[1] = 0 }
            lastSampleNanos = nanos

            out.buttons = buttonsMask
            out.leftTrigger = leftTrigger
            out.rightTrigger = rightTrigger
            out.thumbLX = thumbLX
            out.thumbLY = thumbLY
            out.thumbRX = if (touchDragCameraEnabled) cameraOut[0] else rightStickOut[0]
            out.thumbRY = if (touchDragCameraEnabled) cameraOut[1] else rightStickOut[1]

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
        c.radius = c.spec.radiusDp * c.place.scale * density
        if (width <= 0 || height <= 0) return
        if (c.spec.id == "more" && c.place.anchorLocked && !editModeEnabled) {
            c.place.fx = if (c.place.fx < .5f) .07f else .93f
            c.place.fy = c.place.fy.coerceIn(.12f, .88f)
        }
        val marginX = ((c.radius * RESIZE_HANDLE_OFFSET + RESIZE_HANDLE_RADIUS_DP * density) / width).coerceAtMost(.45f)
        val marginY = ((c.radius * RESIZE_HANDLE_OFFSET + RESIZE_HANDLE_RADIUS_DP * density) / height).coerceAtMost(.45f)
        c.place.fx = c.place.fx.coerceIn(marginX, 1f - marginX)
        c.place.fy = c.place.fy.coerceIn(marginY, 1f - marginY)
        c.cx = c.place.fx * width
        c.cy = c.place.fy * height
        if (c.spec.kind == ControlKind.GROUP_TOGGLE || c.place.drawerId != null || c.place.inMore) repositionDrawers()
    }

    private fun repositionDrawers() {
        if (width <= 0 || height <= 0) return
        val launchers = buttons.filter { it.spec.kind == ControlKind.GROUP_TOGGLE && it.place.visible }
        launchers.forEach { launcher ->
            val grouped = buttons.filter {
                it !== launcher && (it.place.drawerId == launcher.spec.id ||
                    (launcher.spec.id == "more" && it.place.inMore && it.place.drawerId == null))
            }
            grouped.forEachIndexed { index, control ->
                val row = index / 3 + 1
                val column = index % 3
                val directionX = if (launcher.place.fx >= .5f) -1f else 1f
                val directionY = if (launcher.place.fy >= .5f) -1f else 1f
                val stepX = launcher.radius + control.radius + 8f * density
                val stepY = launcher.radius + control.radius + 8f * density
                val targetX = (launcher.cx + directionX * column * stepX).coerceIn(control.radius, width - control.radius)
                val targetY = (launcher.cy + directionY * row * stepY).coerceIn(control.radius, height - control.radius)
                val expansion = when {
                    editModeEnabled -> 1f
                    expandedDrawerId == launcher.spec.id -> menuExpansion
                    else -> 0f
                }
                control.cx = launcher.cx + (targetX - launcher.cx) * expansion
                control.cy = launcher.cy + (targetY - launcher.cy) * expansion
            }
        }
    }

    private fun animateDrawer(open: Boolean, drawerId: String) {
        if (open) {
            expandedDrawerId = drawerId
            drawerOpen = true
        } else if (expandedDrawerId == drawerId) {
            drawerOpen = false
        }
        moreMenuAnimator?.cancel()
        moreMenuAnimator = ValueAnimator.ofFloat(menuExpansion, if (open) 1f else 0f).apply {
            duration = 190
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                menuExpansion = it.animatedValue as Float
                repositionDrawers()
                if (!drawerOpen && menuExpansion <= 0f) expandedDrawerId = null
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun isShown(c: Control) = when {
        hudHidden && c.spec.id != "hud" -> false
        c.spec.kind == ControlKind.RIGHT_STICK -> !touchDragCameraEnabled
        c.spec.kind == ControlKind.GROUP_TOGGLE -> true
        c.place.drawerId != null || c.place.inMore -> (editModeEnabled || (expandedDrawerId == (c.place.drawerId ?: "more") && menuExpansion > .02f)) && (editModeEnabled || touchpadAvailable || (c.spec.kind != ControlKind.TOUCHPAD_TOGGLE && c.spec.kind != ControlKind.KEYBOARD))
        else -> editModeEnabled || touchpadAvailable || (c.spec.kind != ControlKind.TOUCHPAD_TOGGLE && c.spec.kind != ControlKind.KEYBOARD)
    }

    private fun isUsable(c: Control) = isShown(c) && c.place.visible

    private fun buttonAt(x: Float, y: Float): Control? = buttons
        .filter { it.spec.kind != ControlKind.RIGHT_STICK && isUsable(it) && hypot(x - it.cx, y - it.cy) <= it.radius * HIT_SLOP }
        .minByOrNull { hypot(x - it.cx, y - it.cy) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editModeEnabled) return onEditTouch(e)

        // Otherwise Android holds touches back until the next screen refresh.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(e)
            showHudToggleTemporarily()
        }

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
        !stick.place.visible -> false
        stickActive -> false
        stickLocked -> hypot(x - stick.cx, y - stick.cy) <= stick.radius * STICK_GRAB
        else -> x < width * STICK_ZONE
    }

    private fun startsRightStick(x: Float, y: Float): Boolean = when {
        touchDragCameraEnabled || !rightStick.place.visible || rightStickActive -> false
        stickLocked -> hypot(x - rightStick.cx, y - rightStick.cy) <= rightStick.radius * STICK_GRAB
        else -> x > width * (1f - STICK_ZONE)
    }

    private fun pointerDown(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        val id = e.getPointerId(index)

        val rightStickHit = startsRightStick(x, y)
        val hit = if (rightStickHit) rightStick else buttonAt(x, y)
        expandedDrawerId?.let { openId ->
            if (drawerOpen && hit?.spec?.id != openId && hit?.place?.drawerId != openId && !(openId == "more" && hit?.place?.inMore == true)) {
                animateDrawer(false, openId)
            }
        }
        val target = when {
            rightStickHit -> Target.RIGHT_STICK
            hit != null -> Target.BUTTON
            touchpadMode -> Target.PAD
            startsStick(x, y) -> Target.STICK
            touchDragCameraEnabled && cameraActive -> Target.CAMERA
            else -> Target.IGNORE // Consume unused screen touches without moving the camera.
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
            Target.RIGHT_STICK -> {
                rightStickActive = true
                rightStickCenterX = if (stickLocked) rightStick.cx else x
                rightStickCenterY = if (stickLocked) rightStick.cy else y
                updateRightStick(x, y)
            }
            Target.PAD -> {
                if (padPointers() == 1) {
                    padPeakPointers = 0
                    padDragged = false
                }
                padPeakPointers = maxOf(padPeakPointers, padPointers())
            }
            Target.CAMERA -> Unit
            Target.IGNORE -> Unit
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

    private fun updateRightStick(x: Float, y: Float) {
        rightThumbX = x
        rightThumbY = y
        InputNormalizer.stick(x - rightStickCenterX, y - rightStickCenterY, rightStick.radius, rightStickOut)
    }

    private fun pointerMove(e: MotionEvent, index: Int) {
        val track = tracks[e.getPointerId(index)] ?: return
        val x = e.getX(index)
        val y = e.getY(index)

        when (track.target) {
            Target.STICK -> updateStick(x, y)
            Target.RIGHT_STICK -> updateRightStick(x, y)

            Target.CAMERA -> {
                if (touchDragCameraEnabled && cameraActive) {
                    val dx = (x - track.lastX) / density
                    val dy = (y - track.lastY) / density
                    if (dx != 0f || dy != 0f) {
                        synchronized(this) { cameraMotion.add(dx, -dy, cameraGainX, cameraGainY) }
                    }
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
            Target.IGNORE -> Unit
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
                        ControlKind.HUD_TOGGLE -> {
                            if (eventTime - track.downTime >= HUD_LONG_PRESS_MS) {
                                hudHidden = false
                                onLayoutEditRequested?.invoke()
                            } else hudHidden = !hudHidden
                            showHudToggleTemporarily()
                        }
                        else -> Unit
                    }
                    (track.button.place.drawerId ?: if (track.button.place.inMore) "more" else null)?.let { animateDrawer(false, it) }
                }
                invalidate()
            }
            Target.STICK -> {
                stickActive = false
                synchronized(this) {
                    thumbLX = 0
                    thumbLY = 0
                }
            }
            Target.RIGHT_STICK -> {
                rightStickActive = false
                rightStickOut[0] = 0
                rightStickOut[1] = 0
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
            Target.IGNORE -> Unit
        }
    }

    private fun padPointers() = tracks.values.count { it.target == Target.PAD }

    private fun press(b: Control) {
        b.pressCount++
        when (b.spec.kind) {
            ControlKind.GROUP_TOGGLE -> animateDrawer(!(drawerOpen && expandedDrawerId == b.spec.id), b.spec.id)
            ControlKind.HUD_TOGGLE -> Unit
            ControlKind.RIGHT_STICK -> Unit
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
            ControlKind.HUD_TOGGLE -> Unit
            ControlKind.RIGHT_STICK -> Unit
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
                if (previewScene == HudPreviewScene.GAME_SCREEN && drawControllerPreview && isOnScreenResizeHandle(x, y)) {
                    screenResizeGesture = true
                    screenResizeStartScale = previewScreenScale
                    val bounds = gameScreenBounds()
                    screenResizeStartDistance = hypot(x - bounds.centerX(), y - bounds.centerY()).coerceAtLeast(1f)
                    selected = null
                    onSelectionChanged?.invoke(null)
                    invalidate()
                    return true
                }
                val selectedControl = selected
                if (selectedControl != null && isOnRemoveHandle(selectedControl, x, y)) {
                    selectedControl.place.visible = false
                    selected = null
                    resizeGesture = false
                    onSelectionChanged?.invoke(null)
                    invalidate()
                    return true
                }
                if (selectedControl != null && isOnResizeHandle(selectedControl, x, y)) {
                    resizeGesture = true
                    resizeStartScale = selectedControl.place.scale
                    resizeStartDistance = hypot(x - selectedControl.cx, y - selectedControl.cy).coerceAtLeast(1f)
                    return true
                }
                resizeGesture = false
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
            MotionEvent.ACTION_MOVE -> {
                if (screenResizeGesture) {
                    val bounds = gameScreenBounds()
                    val distance = hypot(x - bounds.centerX(), y - bounds.centerY())
                    setPreviewScreenScale((screenResizeStartScale * distance / screenResizeStartDistance).toInt())
                    onPreviewScreenScaleChanged?.invoke(previewScreenScale)
                } else selected?.let {
                    if (resizeGesture) {
                        val distance = hypot(x - it.cx, y - it.cy)
                        layout.resize(it.spec.id, resizeStartScale * distance / resizeStartDistance - it.place.scale)
                        place(it)
                    } else {
                        layout.move(it.spec.id, (x - grabDx) / width, (y - grabDy) / height)
                        place(it)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && selected?.spec?.id == "more" && selected?.place?.anchorLocked == true) {
                    selected?.place?.apply {
                        fx = if (fx < .5f) .07f else .93f
                        fy = fy.coerceIn(.12f, .88f)
                    }
                    placeControls()
                }
                resizeGesture = false
                screenResizeGesture = false
            }
        }
        invalidate()
        return true
    }

    private fun isOnResizeHandle(control: Control, x: Float, y: Float): Boolean {
        val offset = control.radius * RESIZE_HANDLE_OFFSET
        val handleRadius = RESIZE_HANDLE_RADIUS_DP * density
        return listOf(-1f, 1f).any { sx -> listOf(-1f, 1f).any { sy ->
            hypot(x - (control.cx + sx * offset), y - (control.cy + sy * offset)) <= handleRadius
        } }
    }

    private fun isOnRemoveHandle(control: Control, x: Float, y: Float): Boolean {
        val offset = control.radius * RESIZE_HANDLE_OFFSET
        return hypot(x - (control.cx + offset), y - (control.cy - offset)) <= REMOVE_HANDLE_RADIUS_DP * density
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

    fun setAllVisible(visible: Boolean) {
        layout.setAllVisible(visible)
        invalidate()
    }

    fun setSelectedLabel(label: String) {
        selected?.place?.customLabel = label.take(18).ifBlank { null }
        invalidate()
    }

    fun selectedIconPreset(): String = selected?.place?.iconPreset ?: "default"
    fun selectedCustomSvg(): String = selected?.place?.customSvg.orEmpty()

    fun setSelectedIconPreset(preset: String) {
        if (preset !in HudLayout.ICON_PRESETS) return
        selected?.place?.apply { iconPreset = preset; customSvg = null; imageUri = null }
        invalidate()
    }

    fun setSelectedCustomSvg(svg: String): Boolean {
        val selectedControl = selected ?: return false
        val parsed = if (svg.length <= 16 * 1024) SvgIconParser.parse(svg) else null
        if (parsed == null) return false
        customIcons[svg] = parsed
        selectedControl.place.apply { customSvg = svg; imageUri = null; iconPreset = "default" }
        invalidate()
        return true
    }

    fun clearSelectedIcon() {
        selected?.place?.apply { customSvg = null; imageUri = null; iconPreset = "default" }
        invalidate()
    }

    fun toggleMoreAnchorLock(): Boolean {
        val launcher = controls.first { it.spec.id == "more" }
        launcher.place.anchorLocked = !launcher.place.anchorLocked
        if (launcher.place.anchorLocked) {
            launcher.place.fx = if (launcher.place.fx < .5f) .07f else .93f
            launcher.place.fy = launcher.place.fy.coerceIn(.12f, .88f)
            place(launcher)
        }
        invalidate()
        return launcher.place.anchorLocked
    }

    fun moreAnchorLocked() = controls.first { it.spec.id == "more" }.place.anchorLocked

    fun setSelectedColor(color: Int) {
        selected?.place?.color = color
        invalidate()
    }

    fun setSelectedShape(shape: Int) {
        selected?.place?.shape = shape.coerceIn(0, 2)
        invalidate()
    }

    fun setSelectedOpacity(opacity: Int) {
        selected?.place?.opacity = opacity.coerceIn(10, 100)
        invalidate()
    }

    fun setSelectedScale(scale: Float) {
        val c = selected ?: return
        c.place.scale = scale.coerceIn(HudLayout.MIN_SCALE, HudLayout.MAX_SCALE)
        place(c)
        invalidate()
    }

    fun selectControl(id: String?) {
        selected = controls.firstOrNull { it.spec.id == id }
        selected?.let {
            val drawerId = it.place.drawerId ?: if (it.place.inMore) "more" else null
            if (drawerId != null) {
                expandedDrawerId = drawerId
                drawerOpen = true
                menuExpansion = 1f
                repositionDrawers()
            }
        }
        onSelectionChanged?.invoke(id)
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
        control.place.customSvg = null
        control.place.iconPreset = "default"
        invalidate()
    }

    fun toggleSelectedGroup() {
        val control = selected ?: return
        if (control.spec.id == "more" || control.spec.kind == ControlKind.STICK) return
        val currentDrawer = control.place.drawerId ?: if (control.place.inMore) "more" else null
        moveSelectedToDrawer(if (currentDrawer == null) "more" else null)
    }

    fun moveSelectedToDrawer(drawerId: String?) {
        val control = selected ?: return
        if (control.spec.kind == ControlKind.STICK || control.spec.kind == ControlKind.HUD_TOGGLE || control.spec.kind == ControlKind.GROUP_TOGGLE) return
        if (drawerId != null && drawerId !in layout.drawerIds()) return
        control.place.drawerId = drawerId
        control.place.inMore = drawerId == "more"
        if (drawerId != null) {
            control.place.visible = true
            expandedDrawerId = drawerId
            drawerOpen = true
            menuExpansion = 1f
        }
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

    fun refreshLayout() {
        selected = null
        placeControls()
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (editModeEnabled && drawControllerPreview) {
            if (previewScene == HudPreviewScene.CONTROLLER) drawControllerBody(canvas) else drawGameScreenPreview(canvas)
        }
        for (b in buttons) {
            if (!isShown(b) || !b.place.visible) continue
            if (b.spec.kind == ControlKind.RIGHT_STICK) continue

            if (b.spec.kind == ControlKind.HUD_TOGGLE) {
                drawHudVisibilityIcon(canvas, b, if (!editModeEnabled && streamConnected) hudToggleAlpha else 1f)
                if (editModeEnabled && b === selected) drawResizeHandles(canvas, b)
                continue
            }

            val active = b.pressCount > 0 || (b.spec.kind == ControlKind.TOUCHPAD_TOGGLE && touchpadMode)
            val color = b.place.color
            fill.color = Color.argb(alpha(if (active) 150 else 50, b.place.opacity), Color.red(color), Color.green(color), Color.blue(color))
            when (b.place.shape) {
                1 -> canvas.drawRect(b.cx - b.radius, b.cy - b.radius, b.cx + b.radius, b.cy + b.radius, fill)
                2 -> canvas.drawRoundRect(RectF(b.cx - b.radius, b.cy - b.radius, b.cx + b.radius, b.cy + b.radius), b.radius * .35f, b.radius * .35f, fill)
                else -> canvas.drawCircle(b.cx, b.cy, b.radius, fill)
            }
            val image = b.place.imageUri?.let(::loadCustomImage)
            if (image != null) {
                imageBounds.set(b.cx - b.radius, b.cy - b.radius, b.cx + b.radius, b.cy + b.radius)
                imageClip.reset()
                imageClip.addCircle(b.cx, b.cy, b.radius, Path.Direction.CW)
                val checkpoint = canvas.save()
                canvas.clipPath(imageClip)
                imagePaint.alpha = alpha(255, b.place.opacity)
                canvas.drawBitmap(image, null, imageBounds, imagePaint)
                canvas.restoreToCount(checkpoint)
            }
            if (b === selected) canvas.drawCircle(b.cx, b.cy, b.radius, selectionRing)
            else drawRing(canvas, b.cx, b.cy, b.radius, b.place.opacity)
            if (editModeEnabled && b === selected) drawResizeHandles(canvas, b)
            val label = if (b.spec.kind == ControlKind.GROUP_TOGGLE) "" else b.place.customLabel ?: b.spec.label
            val drawerId = b.place.drawerId ?: if (b.place.inMore) "more" else null
            val expansionAlpha = if (drawerId != null) menuExpansion.coerceIn(0f, 1f) else 1f
            if (label.isNotBlank()) {
                canvas.saveLayerAlpha(b.cx - b.radius, b.cy - b.radius, b.cx + b.radius, b.cy + b.radius,
                    (255 * expansionAlpha * b.place.opacity / 100f).toInt().coerceIn(0, 255))
                canvas.drawText(label, b.cx, b.cy - (text.ascent() + text.descent()) / 2f, text)
                canvas.restore()
            } else if (b.place.customSvg != null) {
                drawCustomSvg(canvas, b.place.customSvg!!, b, expansionAlpha * b.place.opacity / 100f)
            } else if (b.place.iconPreset != "default") {
                drawPresetIcon(canvas, b, b.place.iconPreset, expansionAlpha * b.place.opacity / 100f)
            }
        }

        if (!hudHidden && !touchDragCameraEnabled && rightStick.place.visible) drawRightStick(canvas)

        if (touchpadMode) {
            canvas.drawText("Touchpad: drag to move, tap to click, two-finger tap to right-click", width / 2f, height - 16f * density, text)
            return
        }

        if (hudHidden || !stick.place.visible) return
        val cx = if (stickActive) stickCenterX else stick.cx
        val cy = if (stickActive) stickCenterY else stick.cy
        fill.color = Color.argb(alpha(35, stick.place.opacity), 255, 255, 255)
        canvas.drawCircle(cx, cy, stick.radius, fill)
        if (stick === selected) canvas.drawCircle(cx, cy, stick.radius, selectionRing)
        else drawRing(canvas, cx, cy, stick.radius, stick.place.opacity)
        if (editModeEnabled && stick === selected) drawResizeHandles(canvas, stick)

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
        fill.color = Color.argb(alpha(if (stickActive) 140 else 70, stick.place.opacity), 255, 255, 255)
        canvas.drawCircle(cx + kx, cy + ky, stick.radius * 0.42f, fill)
    }

    private fun drawRightStick(canvas: Canvas) {
        val cx = if (rightStickActive) rightStickCenterX else rightStick.cx
        val cy = if (rightStickActive) rightStickCenterY else rightStick.cy
        fill.color = Color.argb(alpha(35, rightStick.place.opacity), 255, 255, 255)
        canvas.drawCircle(cx, cy, rightStick.radius, fill)
        if (rightStick === selected) canvas.drawCircle(cx, cy, rightStick.radius, selectionRing)
        else drawRing(canvas, cx, cy, rightStick.radius, rightStick.place.opacity)
        if (editModeEnabled && rightStick === selected) drawResizeHandles(canvas, rightStick)
        var dx = if (rightStickActive) rightThumbX - rightStickCenterX else 0f
        var dy = if (rightStickActive) rightThumbY - rightStickCenterY else 0f
        val distance = hypot(dx, dy)
        if (distance > rightStick.radius) {
            dx *= rightStick.radius / distance
            dy *= rightStick.radius / distance
        }
        fill.color = Color.argb(alpha(if (rightStickActive) 140 else 70, rightStick.place.opacity), 255, 255, 255)
        canvas.drawCircle(cx + dx, cy + dy, rightStick.radius * .42f, fill)
    }

    private fun drawCustomSvg(canvas: Canvas, source: String, control: Control, opacity: Float) {
        val icon = (if (customIcons.containsKey(source)) customIcons[source] else SvgIconParser.parse(source).also { customIcons[source] = it }) ?: return
        val size = control.radius * .68f
        val scale = minOf(size / icon.width, size / icon.height)
        val save = canvas.save()
        canvas.translate(control.cx - icon.width * scale / 2f, control.cy - icon.height * scale / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-icon.minX, -icon.minY)
        iconPaint.alpha = (255 * opacity).toInt()
        iconPaint.color = Color.WHITE
        iconPaint.style = Paint.Style.FILL
        for (path in icon.paths) canvas.drawPath(path, iconPaint)
        canvas.restoreToCount(save)
    }

    private fun drawPresetIcon(canvas: Canvas, control: Control, preset: String, opacity: Float) {
        val size = control.radius * .58f
        val s = size / 24f
        val save = canvas.save()
        canvas.translate(control.cx - size, control.cy - size)
        canvas.scale(s, s)
        iconPaint.alpha = (245 * opacity).toInt()
        iconPaint.strokeWidth = 2f
        iconPaint.style = Paint.Style.STROKE
        when (preset) {
            "more" -> {
                iconPaint.style = Paint.Style.FILL
                for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.7f, iconPaint)
            }
            "drawer" -> {
                canvas.drawRoundRect(RectF(3f, 4f, 21f, 20f), 2.5f, 2.5f, iconPaint)
                canvas.drawLine(3.5f, 9f, 20.5f, 9f, iconPaint)
                canvas.drawLine(9f, 14.5f, 15f, 14.5f, iconPaint)
            }
            "folder" -> {
                val folder = Path().apply {
                    moveTo(2f, 7f); lineTo(9f, 7f); lineTo(11f, 9f); lineTo(22f, 9f)
                    lineTo(20f, 19f); lineTo(4f, 19f); close()
                }
                canvas.drawPath(folder, iconPaint)
                canvas.drawLine(3f, 10f, 21f, 10f, iconPaint)
            }
            "eye" -> {
                val p = Path().apply { moveTo(2f, 12f); quadTo(12f, 2f, 22f, 12f); quadTo(12f, 22f, 2f, 12f); close() }
                canvas.drawPath(p, iconPaint); canvas.drawCircle(12f, 12f, 3f, iconPaint)
            }
            "gamepad" -> {
                val p = Path().apply { moveTo(6f, 7f); lineTo(18f, 7f); quadTo(21f, 7f, 22f, 18f); quadTo(22f, 21f, 19f, 19f); lineTo(16f, 16f); lineTo(8f, 16f); lineTo(5f, 19f); quadTo(2f, 21f, 2f, 18f); quadTo(3f, 7f, 6f, 7f); close() }
                canvas.drawPath(p, iconPaint); canvas.drawLine(6f, 11f, 10f, 11f, iconPaint); canvas.drawLine(8f, 9f, 8f, 13f, iconPaint)
                canvas.drawCircle(16f, 10f, 1f, iconPaint); canvas.drawCircle(19f, 13f, 1f, iconPaint)
            }
            "keyboard" -> {
                canvas.drawRoundRect(RectF(2f, 5f, 22f, 19f), 2f, 2f, iconPaint)
                for (y in listOf(9f, 13f)) for (x in listOf(6f, 10f, 14f, 18f)) canvas.drawCircle(x, y, .6f, iconPaint)
                canvas.drawLine(7f, 16f, 17f, 16f, iconPaint)
            }
            "back" -> { canvas.drawLine(20f, 12f, 4f, 12f, iconPaint); canvas.drawLine(4f, 12f, 11f, 5f, iconPaint); canvas.drawLine(4f, 12f, 11f, 19f, iconPaint) }
            "camera" -> { canvas.drawRoundRect(RectF(3f, 7f, 21f, 19f), 2f, 2f, iconPaint); canvas.drawCircle(12f, 13f, 4f, iconPaint); canvas.drawLine(7f, 7f, 9f, 4f, iconPaint) }
            "target" -> { canvas.drawCircle(12f, 12f, 9f, iconPaint); canvas.drawCircle(12f, 12f, 4f, iconPaint); canvas.drawLine(12f, 1f, 12f, 6f, iconPaint); canvas.drawLine(12f, 18f, 12f, 23f, iconPaint) }
            "star" -> {
                val p = Path(); for (i in 0..9) { val a = Math.PI * i / 5.0 - Math.PI / 2; val r = if (i % 2 == 0) 10f else 4.5f; val x = (12 + kotlin.math.cos(a) * r).toFloat(); val y = (12 + kotlin.math.sin(a) * r).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }; p.close(); canvas.drawPath(p, iconPaint)
            }
        }
        iconPaint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawHudVisibilityIcon(canvas: Canvas, control: Control, opacity: Float) {
        if (opacity <= 0f) return
        val size = minOf(control.radius * 1.55f, 36f * density)
        val scale = size / 24f
        val save = canvas.save()
        canvas.translate(control.cx - size / 2f, control.cy - size / 2f)
        canvas.scale(scale, scale)
        iconPaint.color = Color.WHITE
        iconPaint.alpha = (alpha(245, control.place.opacity) * opacity).toInt().coerceIn(0, 255)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 2.25f
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        val eye = Path().apply {
            moveTo(2f, 12f)
            cubicTo(5f, 8f, 8.4f, 6f, 12f, 6f)
            cubicTo(15.6f, 6f, 19f, 8f, 22f, 12f)
            cubicTo(19f, 16f, 15.6f, 18f, 12f, 18f)
            cubicTo(8.4f, 18f, 5f, 16f, 2f, 12f)
        }
        canvas.drawPath(eye, iconPaint)
        iconPaint.style = Paint.Style.FILL
        canvas.drawCircle(12f, 12f, 4f, iconPaint)
        iconPaint.color = Color.rgb(12, 34, 49)
        iconPaint.alpha = (alpha(220, control.place.opacity) * opacity).toInt().coerceIn(0, 255)
        canvas.drawCircle(12f, 12f, 1.65f, iconPaint)
        if (hudHidden) {
            iconPaint.color = Color.rgb(255, 116, 108)
            iconPaint.alpha = (alpha(255, control.place.opacity) * opacity).toInt().coerceIn(0, 255)
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 2.5f
            canvas.drawLine(4f, 20f, 20f, 4f, iconPaint)
        }
        iconPaint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawControllerBody(canvas: Canvas) {
        controllerModelBitmap?.let { model ->
            controllerModelBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            imagePaint.alpha = 255
            canvas.drawBitmap(model, null, controllerModelBounds, imagePaint)
            text.textSize = 10f * density
            text.color = Color.argb(170, 215, 224, 239)
            canvas.drawText("XBOX LAYOUT  ·  DRAG TO POSITION", width / 2f, height * .085f, text)
            text.textSize = 14f * density
            text.color = Color.argb(alpha(210), 255, 255, 255)
            return
        }
        if (controllerGradientWidth != width || controllerGradientHeight != height) {
            controllerGradientWidth = width
            controllerGradientHeight = height
            controllerFill.shader = LinearGradient(0f, height * .18f, 0f, height * .94f,
                intArrayOf(Color.rgb(55, 67, 83), Color.rgb(22, 29, 39)), null, Shader.TileMode.CLAMP)
        }
        controllerBody.reset()
        controllerBody.moveTo(width * .22f, height * .20f)
        controllerBody.cubicTo(width * .35f, height * .16f, width * .65f, height * .16f, width * .78f, height * .20f)
        controllerBody.cubicTo(width * .88f, height * .23f, width * .90f, height * .32f, width * .92f, height * .45f)
        controllerBody.lineTo(width * .98f, height * .81f)
        controllerBody.cubicTo(width * 1.00f, height * .93f, width * .91f, height * .96f, width * .84f, height * .90f)
        controllerBody.lineTo(width * .68f, height * .76f)
        controllerBody.cubicTo(width * .59f, height * .72f, width * .41f, height * .72f, width * .32f, height * .76f)
        controllerBody.lineTo(width * .16f, height * .90f)
        controllerBody.cubicTo(width * .09f, height * .96f, width * .00f, height * .93f, width * .02f, height * .81f)
        controllerBody.lineTo(width * .08f, height * .45f)
        controllerBody.cubicTo(width * .10f, height * .32f, width * .12f, height * .23f, width * .22f, height * .20f)
        controllerBody.close()
        canvas.drawPath(controllerBody, controllerFill)
        canvas.drawPath(controllerBody, controllerOutline)

        val panel = RectF(width * .42f, height * .28f, width * .58f, height * .57f)
        canvas.drawRoundRect(panel, 12f * density, 12f * density, controllerOutline)
        canvas.drawLine(panel.left, panel.centerY(), panel.right, panel.centerY(), controllerOutline)
        canvas.drawRoundRect(RectF(width * .12f, height * .18f, width * .31f, height * .26f), 14f * density, 14f * density, controllerOutline)
        canvas.drawRoundRect(RectF(width * .69f, height * .18f, width * .88f, height * .26f), 14f * density, 14f * density, controllerOutline)
        canvas.drawLine(width * .24f, height * .29f, width * .76f, height * .29f, controllerDetail)
        canvas.drawCircle(width * .16f, height * .78f, stick.radius * 1.12f, controllerDetail)
        canvas.drawCircle(width * .72f, height * .72f, 43f * density, controllerDetail)
        text.textSize = 10f * density
        text.color = Color.argb(135, 215, 224, 239)
        canvas.drawText("XBOX LAYOUT  ·  DRAG TO POSITION", width / 2f, height * .085f, text)
        text.textSize = 14f * density
        text.color = Color.argb(alpha(210), 255, 255, 255)
    }

    private fun drawGameScreenPreview(canvas: Canvas) {
        val screen = gameScreenBounds()
        val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(screen.left, screen.top, screen.right, screen.bottom,
                intArrayOf(Color.rgb(12, 33, 59), Color.rgb(20, 76, 111), Color.rgb(23, 36, 61)), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(screen, 14f * density, 14f * density, screenFill)

        val horizon = screen.top + screen.height() * .69f
        val scene = Path().apply {
            moveTo(screen.left, horizon)
            lineTo(screen.left + screen.width() * .23f, horizon - screen.height() * .21f)
            lineTo(screen.left + screen.width() * .48f, horizon + screen.height() * .05f)
            lineTo(screen.left + screen.width() * .71f, horizon - screen.height() * .18f)
            lineTo(screen.right, horizon + screen.height() * .03f)
            lineTo(screen.right, screen.bottom)
            lineTo(screen.left, screen.bottom)
            close()
        }
        val landscapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(66, 18, 27, 45) }
        canvas.drawPath(scene, landscapePaint)
        val handleRadius = SCREEN_HANDLE_RADIUS_DP * density
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(71, 211, 177) }
        val handleOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 231, 255, 249)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        screenHandlePoints(screen).forEach { (x, y) ->
            val handle = RectF(x - handleRadius, y - handleRadius, x + handleRadius, y + handleRadius)
            canvas.drawRoundRect(handle, 4f * density, 4f * density, handleFill)
            canvas.drawRoundRect(handle, 4f * density, 4f * density, handleOutline)
            canvas.drawLine(x - 3.5f * density, y + 3.5f * density,
                x + 3.5f * density, y - 3.5f * density, handleOutline)
        }
        text.textSize = 10f * density
        text.color = Color.argb(210, 230, 242, 255)
        val tagText = "Resize screen  ·  ${previewScreenScale}%"
        val tagWidth = text.measureText(tagText) + 18f * density
        val tagHeight = 24f * density
        val tag = RectF(screen.left + 9f * density, screen.top + 8f * density,
            minOf(screen.left + 9f * density + tagWidth, screen.right - 8f * density),
            screen.top + 8f * density + tagHeight)
        val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 10, 20, 31) }
        val tagBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 112, 229, 201)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        canvas.drawRoundRect(tag, 8f * density, 8f * density, tagFill)
        canvas.drawRoundRect(tag, 8f * density, 8f * density, tagBorder)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(tagText, tag.left + 9f * density,
            tag.centerY() - (text.ascent() + text.descent()) / 2f, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f * density
        text.color = Color.argb(alpha(210), 255, 255, 255)
    }

    private fun gameScreenBounds(): RectF {
        val inset = 0f
        val availableWidth = (width - 2f * inset).coerceAtLeast(1f)
        val availableHeight = (height - 2f * inset).coerceAtLeast(1f)
        val targetWidth: Float
        val targetHeight: Float
        if (previewStretchToFill) {
            targetWidth = availableWidth * previewScreenScale / 100f
            targetHeight = availableHeight * previewScreenScale / 100f
        } else {
            targetWidth = minOf(availableWidth, availableHeight * (16f / 9f)) * previewScreenScale / 100f
            targetHeight = targetWidth * 9f / 16f
        }
        return RectF((width - targetWidth) / 2f, (height - targetHeight) / 2f,
            (width + targetWidth) / 2f, (height + targetHeight) / 2f)
    }

    private fun isOnScreenResizeHandle(x: Float, y: Float): Boolean {
        val bounds = gameScreenBounds()
        val radius = SCREEN_HANDLE_HIT_DP * density
        return screenHandlePoints(bounds).any { (hx, hy) -> hypot(x - hx, y - hy) <= radius }
    }

    private fun screenHandlePoints(bounds: RectF): List<Pair<Float, Float>> {
        val inset = (SCREEN_HANDLE_RADIUS_DP + 2f) * density
        return listOf(
            (bounds.left + inset) to (bounds.top + inset),
            (bounds.right - inset) to (bounds.top + inset),
            (bounds.left + inset) to (bounds.bottom - inset),
            (bounds.right - inset) to (bounds.bottom - inset),
        )
    }

    private fun drawResizeHandles(canvas: Canvas, control: Control) {
        val half = control.radius * RESIZE_HANDLE_OFFSET
        val bounds = RectF(control.cx - half, control.cy - half, control.cx + half, control.cy + half)
        val oldColor = selectionRing.color
        selectionRing.color = Color.argb(170, 255, 255, 255)
        canvas.drawRect(bounds, selectionRing)
        selectionRing.color = oldColor
        val nearestMatch = controls.asSequence()
            .filter { it !== control }
            .minByOrNull { kotlin.math.abs(it.radius - control.radius) }
            ?.takeIf { kotlin.math.abs(it.radius - control.radius) <= 4f * density }
        val sizeLabel = buildString {
            append("${(control.place.scale * 100f).toInt()}%  ·  ${(control.radius * 2f / density).toInt()} dp")
            nearestMatch?.let {
                val name = it.spec.label.ifBlank { if (it.spec.id == "stick") "Left stick" else it.spec.id }
                append("  ·  matches $name")
            }
        }
        text.textSize = 10f * density
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        val tagWidth = text.measureText(sizeLabel) + 18f * density
        val tagHeight = 22f * density
        val tagCenterX = if (tagWidth + 8f * density >= width) width / 2f else
            bounds.centerX().coerceIn(tagWidth / 2f + 4f * density, width - tagWidth / 2f - 4f * density)
        val tagTop = (bounds.top - tagHeight - 6f * density).coerceAtLeast(4f * density)
        val tag = RectF(tagCenterX - tagWidth / 2f, tagTop, tagCenterX + tagWidth / 2f, tagTop + tagHeight)
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 9, 18, 29) }
        canvas.drawRoundRect(tag, 8f * density, 8f * density, tagPaint)
        canvas.drawText(sizeLabel, tag.centerX(), tag.centerY() - (text.ascent() + text.descent()) / 2f, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14f * density
        text.color = Color.WHITE
        fill.color = Color.rgb(255, 214, 0)
        val handleRadius = RESIZE_HANDLE_RADIUS_DP * density
        canvas.drawCircle(bounds.left, bounds.top, handleRadius, fill)
        canvas.drawCircle(bounds.left, bounds.bottom, handleRadius, fill)
        canvas.drawCircle(bounds.right, bounds.bottom, handleRadius, fill)
        fill.color = Color.rgb(239, 68, 68)
        val removeRadius = REMOVE_HANDLE_RADIUS_DP * density
        canvas.drawCircle(bounds.right, bounds.top, removeRadius, fill)
        val crossRadius = removeRadius * .38f
        canvas.drawLine(bounds.right - crossRadius, bounds.top - crossRadius,
            bounds.right + crossRadius, bounds.top + crossRadius, removeHandleIcon)
        canvas.drawLine(bounds.right + crossRadius, bounds.top - crossRadius,
            bounds.right - crossRadius, bounds.top + crossRadius, removeHandleIcon)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, opacity: Int) {
        val originalAlpha = ring.alpha
        ring.alpha = originalAlpha * opacity / 100
        canvas.drawCircle(cx, cy, radius, ring)
        ring.alpha = originalAlpha
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
        const val RESIZE_HANDLE_OFFSET = 1.35f
        const val RESIZE_HANDLE_RADIUS_DP = 14f
        const val REMOVE_HANDLE_RADIUS_DP = 18f
        const val SCREEN_HANDLE_RADIUS_DP = 11f
        const val SCREEN_HANDLE_HIT_DP = 32f
        const val SCREEN_EDGE_INSET_DP = 22f
        const val BASE_CAMERA_GAIN = 0.75f
        const val TAP_SLOP_DP = 10f
        const val TAP_MAX_MS = 250L
        const val CLICK_HOLD_MS = 40L
        const val HUD_LONG_PRESS_MS = 550L
        const val HUD_ICON_VISIBLE_MS = 1_000L
        const val HUD_ICON_FADE_MS = 350L
        const val MOUSE_SENSITIVITY = 1.8f
        const val MAX_CUSTOM_IMAGE_EDGE = 512
    }
}
