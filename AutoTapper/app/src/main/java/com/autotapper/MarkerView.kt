package com.autotapper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The visual marker for one tap point, drawn inside its own overlay window.
 *
 * - Circle you resize by dragging the handle (bottom-right). Bigger circle =
 *   wider random-tap area.
 * - Shrunk below [crosshairRadiusPx] it becomes a crosshair: the red centre
 *   dot is the exact aim pixel, no randomness. Resizing is reversible.
 * - Tap to select, tap again to lock (locked = frozen: no move / no resize).
 * - The red dot flashes on each dispatched tap.
 *
 * The window itself is square: side = 2 * (radius + PAD). The centre of the
 * view is the aim point. OverlayController owns window placement; this view
 * reports gestures through [Listener].
 */
@SuppressLint("ViewConstructor")
class MarkerView(
    context: Context,
    val point: TapPoint,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        /** Finger moved the marker by (dx, dy) window pixels. */
        fun onMoveBy(marker: MarkerView, dx: Int, dy: Int)

        /** Radius changed (handle drag); controller resizes + repositions the window. */
        fun onRadiusChanged(marker: MarkerView, newRadiusPx: Int)

        /** Single tap on the marker (select / lock cycling is decided upstream). */
        fun onTapped(marker: MarkerView)

        /** Drag started/ended — controller fades the window so the target shows through. */
        fun onDragState(marker: MarkerView, dragging: Boolean)
    }

    companion object {
        /** Extra window padding around the circle so the handle stays inside. */
        fun padPx(density: Float) = (18 * density).toInt()

        fun minRadiusPx(density: Float) = (10 * density).toInt()
        fun maxRadiusPx(density: Float) = (160 * density).toInt()

        /** At or below this radius the marker renders as an exact crosshair. */
        fun crosshairRadiusPx(density: Float) = (22 * density).toInt()
    }

    var radiusPx: Int = (48 * resources.displayMetrics.density).toInt()
    var selected: Boolean = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val pad = padPx(density)
    private val crosshairAt = crosshairRadiusPx(density)
    val isCrosshair: Boolean get() = radiusPx <= crosshairAt

    /** Random-tap radius: zero in crosshair mode (exact pixel). */
    val effectiveRandomRadius: Int get() = if (isCrosshair) 0 else radiusPx

    private var flashUntil = 0L

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radiusPx.toFloat()
        val accent = if (point.locked) 0xFFFFA000.toInt()
            else if (selected) 0xFF29B6F6.toInt() else 0xFF90CAF9.toInt()

        if (isCrosshair) {
            // Crosshair: exact aim pixel, no randomness.
            linePaint.color = accent
            val arm = crosshairAt * 1.1f
            canvas.drawLine(cx - arm, cy, cx - 4 * density, cy, linePaint)
            canvas.drawLine(cx + 4 * density, cy, cx + arm, cy, linePaint)
            canvas.drawLine(cx, cy - arm, cx, cy - 4 * density, linePaint)
            canvas.drawLine(cx, cy + 4 * density, cx, cy + arm, linePaint)
        } else {
            fillPaint.color = accent and 0x00FFFFFF or 0x22000000
            canvas.drawCircle(cx, cy, r, fillPaint)
            circlePaint.color = accent
            canvas.drawCircle(cx, cy, r, circlePaint)
        }

        // Red centre dot — the exact aim pixel; flashes on each dispatched tap.
        val flashing = System.currentTimeMillis() < flashUntil
        val dotR = (if (flashing) 6f else 3.5f) * density
        dotPaint.color = if (flashing) 0xFFFF1744.toInt() else 0xFFE53935.toInt()
        canvas.drawCircle(cx, cy, dotR, dotPaint)

        // Resize handle at 45° on the rim (only when selected and not locked).
        if (selected && !point.locked) {
            val hx = cx + handleOffset()
            val hy = cy + handleOffset()
            handlePaint.color = accent
            canvas.drawCircle(hx, hy, 7f * density, handlePaint)
            handlePaint.color = Color.WHITE
            canvas.drawCircle(hx, hy, 3f * density, handlePaint)
        }
    }

    private fun handleOffset(): Float {
        val r = if (isCrosshair) crosshairAt * 1.1f else radiusPx.toFloat()
        return (r * 0.7071f) + 6 * density
    }

    fun flash() {
        flashUntil = System.currentTimeMillis() + 180
        invalidate()
        postDelayed({ invalidate() }, 200)
    }

    // --- touch handling ---

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var onHandle = false
    private var moved = false
    private var dragging = false
    private val touchSlop = 6 * density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = event.rawX; lastY = event.rawY
                moved = false
                val hx = width / 2f + handleOffset()
                val hy = height / 2f + handleOffset()
                onHandle = selected && !point.locked &&
                    hypot(event.x - hx, event.y - hy) < 22 * density
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (!moved && hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                    moved = true
                    if (!point.locked) {
                        dragging = true
                        listener.onDragState(this, true)
                    }
                }
                if (moved && !point.locked) {
                    if (onHandle) {
                        // Radial resize: distance from centre along the drag.
                        val delta = ((dx + dy) / 2f).toInt()
                        val newR = (radiusPx + delta)
                            .coerceIn(minRadiusPx(density), maxRadiusPx(density))
                        if (newR != radiusPx) listener.onRadiusChanged(this, newR)
                    } else {
                        if (abs(dx) >= 1 || abs(dy) >= 1) {
                            listener.onMoveBy(this, dx.toInt(), dy.toInt())
                        }
                    }
                }
                lastX = event.rawX; lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    listener.onDragState(this, false)
                }
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    listener.onTapped(this)
                }
                onHandle = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
