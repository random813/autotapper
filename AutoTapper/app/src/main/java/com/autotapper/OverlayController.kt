package com.autotapper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Owns every floating window: the bubble, the bubble menu, the tap-point
 * markers, the selected-marker toolbar, the nudge crosspad and the interval
 * editor. Also runs the tap loop.
 *
 * Interaction rules (from the design):
 *  - Tap a marker to select it; tap the selected marker again to lock it
 *    (locked = frozen and untouchable so nothing drifts). Unlock from the
 *    toolbar's lock button on the right.
 *  - Drag to move, drag the handle to resize; the marker window fades while
 *    dragging so you can see the target underneath.
 *  - While running, the overlay goes near-invisible and untouchable so taps
 *    pass through; only the bubble stays live to stop the run.
 */
class OverlayController(private val context: Context) : MarkerView.Listener {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("autotapper", Context.MODE_PRIVATE)

    private val markers = mutableListOf<MarkerView>()
    private var selected: MarkerView? = null
    private var running = false

    // --- windows ---
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var toolbar: View? = null
    private var toolbarInterval: TextView? = null
    private var toolbarCounter: TextView? = null
    private var toolbarLock: TextView? = null
    private var nudgePad: View? = null
    private var editorPanel: View? = null

    private fun dp(v: Int) = (v * density).toInt()

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
    }

    private val overlayType: Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ------------------------------------------------------------------ show / destroy

    fun show() {
        addBubble()
        TapPoint.listFromJson(prefs.getString("points", null)).forEach { addMarker(it) }
    }

    fun destroy() {
        stopRun()
        save()
        listOfNotNull(menu, toolbar, nudgePad, editorPanel, bubble).forEach { safeRemove(it) }
        markers.forEach { safeRemove(it) }
        markers.clear()
        handler.removeCallbacksAndMessages(null)
    }

    /** Rotation / fold: reposition everything from normalized coordinates. */
    fun onScreenChanged() {
        markers.forEach { layoutMarker(it) }
    }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) { }
    }

    private fun save() {
        prefs.edit().putString("points", TapPoint.listToJson(markers.map { it.point })).apply()
    }

    // ------------------------------------------------------------------ bubble + menu

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(52)
        val v = TextView(context).apply {
            text = "◎"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE263238.toInt())
                setStroke(dp(2), 0xFFE53935.toInt())
            }
        }
        val p = overlayParams(size, size).apply {
            x = screenSize().x - size - dp(8)
            y = screenSize().y / 3
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - downX, e.rawY - downY) > dp(6)) moved = true
                    if (moved) {
                        p.x = startX + (e.rawX - downX).toInt()
                        p.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    true
                }
                else -> false
            }
        }
        wm.addView(v, p)
        bubble = v
        bubbleParams = p
    }

    private fun updateBubbleFace() {
        bubble?.text = if (running) "⏸" else "◎"
        bubble?.alpha = if (running) 0.55f else 1f
    }

    private fun toggleMenu() {
        if (running) { stopRun(); return }
        if (menu != null) { dismissMenu(); return }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.addView(menuItem("＋  Tap point") {
            dismissMenu()
            addMarker(TapPoint())
            save()
        })
        col.addView(menuItem("▶  Start") {
            dismissMenu()
            startRun()
        })
        col.addView(menuItem("✕  Close AutoTapper") {
            dismissMenu()
            context.stopService(android.content.Intent(context, OverlayService::class.java))
        })
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            val bp = bubbleParams!!
            val onLeftHalf = bp.x < screenSize().x / 2
            x = if (onLeftHalf) bp.x + dp(60) else (bp.x - dp(180)).coerceAtLeast(0)
            y = bp.y
        }
        wm.addView(col, p)
        menu = col
    }

    private fun dismissMenu() {
        menu?.let { safeRemove(it) }
        menu = null
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(0xF0263238.toInt())
    }

    // ------------------------------------------------------------------ markers

    private fun addMarker(point: TapPoint) {
        val m = MarkerView(context, point, this)
        m.radiusPx = (point.nr * minOf(screenSize().x, screenSize().y)).toInt()
            .coerceIn(MarkerView.minRadiusPx(density), MarkerView.maxRadiusPx(density))
        val p = overlayParams(10, 10)
        wm.addView(m, p)
        markers.add(m)
        layoutMarker(m)
        select(m)
    }

    private fun markerParams(m: MarkerView): WindowManager.LayoutParams =
        m.layoutParams as WindowManager.LayoutParams

    /** Window side = 2 * (radius + pad); centre = normalized position. */
    private fun layoutMarker(m: MarkerView) {
        val s = screenSize()
        val side = 2 * (maxOf(m.radiusPx, MarkerView.crosshairRadiusPx(density)) +
            MarkerView.padPx(density))
        val p = markerParams(m)
        p.width = side
        p.height = side
        p.x = (m.point.nx * s.x).toInt() - side / 2
        p.y = (m.point.ny * s.y).toInt() - side / 2
        wm.updateViewLayout(m, p)
    }

    private fun markerCentre(m: MarkerView): Point {
        val s = screenSize()
        return Point((m.point.nx * s.x).toInt(), (m.point.ny * s.y).toInt())
    }

    override fun onMoveBy(marker: MarkerView, dx: Int, dy: Int) {
        val s = screenSize()
        val c = markerCentre(marker)
        marker.point.nx = ((c.x + dx).coerceIn(0, s.x)).toDouble() / s.x
        marker.point.ny = ((c.y + dy).coerceIn(0, s.y)).toDouble() / s.y
        layoutMarker(marker)
    }

    override fun onRadiusChanged(marker: MarkerView, newRadiusPx: Int) {
        val s = screenSize()
        marker.radiusPx = newRadiusPx
        marker.point.nr = newRadiusPx.toDouble() / minOf(s.x, s.y)
        layoutMarker(marker)
        marker.invalidate()
    }

    override fun onTapped(marker: MarkerView) {
        if (selected === marker) {
            if (!marker.point.locked) {
                marker.point.locked = true      // tap again to lock
                marker.invalidate()
                updateToolbar()
                save()
            }
        } else {
            select(marker)
        }
    }

    override fun onDragState(marker: MarkerView, dragging: Boolean) {
        // Fade while dragging so the target underneath stays visible.
        marker.alpha = if (dragging) 0.35f else 1f
        if (!dragging) save()
    }

    private fun select(marker: MarkerView?) {
        selected?.selected = false
        selected = marker
        marker?.selected = true
        if (marker == null) hideToolbar() else showToolbar()
        hideNudgePad(); hideEditor()
    }

    private fun deleteSelected() {
        val m = selected ?: return
        select(null)
        safeRemove(m)
        markers.remove(m)
        save()
    }

    // ------------------------------------------------------------------ toolbar

    private fun toolbarButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun showToolbar() {
        hideToolbar()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelBackground()
            setPadding(dp(4), 0, dp(4), 0)
        }
        row.addView(toolbarButton("⚙") { toggleEditor() })
        toolbarInterval = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(dp(4), 0, dp(8), 0)
        }
        row.addView(toolbarInterval)
        toolbarCounter = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 0, dp(6), 0)
        }
        row.addView(toolbarCounter)
        row.addView(toolbarButton("✛") { toggleNudgePad() })
        toolbarLock = toolbarButton("🔓") {
            val m = selected ?: return@toolbarButton
            m.point.locked = !m.point.locked
            m.invalidate()
            updateToolbar()
            save()
        }
        row.addView(toolbarLock)   // lock lives on the right

        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        wm.addView(row, p)
        toolbar = row
        updateToolbar()
    }

    private fun hideToolbar() {
        toolbar?.let { safeRemove(it) }
        toolbar = null
    }

    private fun formatMs(ms: Long): String = when {
        ms >= 60_000L && ms % 60_000L == 0L -> "${ms / 60_000}min"
        ms >= 1000L -> {
            val s = ms / 1000.0
            if (s == s.toLong().toDouble()) "${s.toLong()}s" else "${s}s"
        }
        else -> "${ms}ms"
    }

    private fun updateToolbar() {
        val m = selected ?: return
        toolbarInterval?.text =
            if (m.point.intervalMinMs == m.point.intervalMaxMs)
                "every ${formatMs(m.point.intervalMinMs)}"
            else
                "every ${formatMs(m.point.intervalMinMs)}–${formatMs(m.point.intervalMaxMs)}"
        toolbarCounter?.text = "${m.point.clickCount}×"
        toolbarLock?.text = if (m.point.locked) "🔒" else "🔓"
    }

    // ------------------------------------------------------------------ nudge crosspad ("Move 1 px")

    private fun toggleNudgePad() {
        if (nudgePad != null) { hideNudgePad(); return }
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun nudge(dx: Int, dy: Int): () -> Unit = {
            selected?.let { if (!it.point.locked) { onMoveBy(it, dx, dy); save() } }
        }
        grid.addView(toolbarButton("▲", nudge(0, -1)))
        val mid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(toolbarButton("◀", nudge(-1, 0)))
        mid.addView(toolbarButton("✕") { hideNudgePad() })
        mid.addView(toolbarButton("▶", nudge(1, 0)))
        grid.addView(mid)
        grid.addView(toolbarButton("▼", nudge(0, 1)))

        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(84)
        }
        wm.addView(grid, p)
        nudgePad = grid
    }

    private fun hideNudgePad() {
        nudgePad?.let { safeRemove(it) }
        nudgePad = null
    }

    // ------------------------------------------------------------------ interval editor (settings gear)

    private val unitFactors = longArrayOf(1L, 1000L, 60_000L)
    private val unitNames = arrayOf("ms", "s", "min")

    private fun toggleEditor() {
        if (editorPanel != null) { hideEditor(); return }
        val m = selected ?: return
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        col.addView(TextView(context).apply {
            text = "Interval"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
        })

        // Pick the largest unit that represents both bounds cleanly.
        var unitIx = 0
        for (i in unitFactors.indices.reversed()) {
            val f = unitFactors[i]
            if (m.point.intervalMinMs % f == 0L && m.point.intervalMaxMs % f == 0L &&
                m.point.intervalMinMs >= f
            ) { unitIx = i; break }
        }

        fun numberField(initial: Long) = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((initial / unitFactors[unitIx]).toString())
            setTextColor(Color.WHITE)
            minWidth = dp(56)
        }

        val minField = numberField(m.point.intervalMinMs)
        val maxField = numberField(m.point.intervalMaxMs)
        val unitSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, unitNames)
            setSelection(unitIx)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply { text = "every "; setTextColor(0xFFB0BEC5.toInt()) })
        row.addView(minField)
        row.addView(TextView(context).apply { text = " – "; setTextColor(0xFFB0BEC5.toInt()) })
        row.addView(maxField)
        row.addView(unitSpinner)
        col.addView(row)
        col.addView(TextView(context).apply {
            text = "Same min and max = fixed interval."
            textSize = 12f
            setTextColor(0xFF78909C.toInt())
        })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(context).apply {
            text = "Save"
            setOnClickListener {
                val f = unitFactors[unitSpinner.selectedItemPosition]
                val lo = (minField.text.toString().toLongOrNull() ?: 1L) * f
                val hi = (maxField.text.toString().toLongOrNull() ?: 1L) * f
                m.point.intervalMinMs = minOf(lo, hi).coerceAtLeast(20L)
                m.point.intervalMaxMs = maxOf(lo, hi).coerceAtLeast(20L)
                updateToolbar()
                save()
                hideEditor()
            }
        })
        buttons.addView(Button(context).apply {
            text = "Delete point"
            setOnClickListener { hideEditor(); deleteSelected() }
        })
        buttons.addView(Button(context).apply {
            text = "Close"
            setOnClickListener { hideEditor() }
        })
        col.addView(buttons)

        // Needs keyboard focus, so no FLAG_NOT_FOCUSABLE here.
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(84)
        }
        wm.addView(col, p)
        editorPanel = col
    }

    private fun hideEditor() {
        editorPanel?.let { safeRemove(it) }
        editorPanel = null
    }

    // ------------------------------------------------------------------ run loop

    private fun startRun() {
        if (markers.none { it.point.enabled }) {
            toast("Add a tap point first (＋)")
            return
        }
        if (!TapAccessibilityService.isRunning) {
            toast("Enable AutoTapper in Settings → Accessibility first")
            return
        }
        running = true
        select(null)
        // Near-invisible and untouchable while running so taps pass through.
        markers.forEach {
            it.alpha = 0.25f
            val p = markerParams(it)
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(it, p)
        }
        updateBubbleFace()
        markers.filter { it.point.enabled }.forEach { scheduleNext(it) }
    }

    private fun stopRun() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        markers.forEach {
            it.alpha = 1f
            val p = markerParams(it)
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { wm.updateViewLayout(it, p) } catch (_: Exception) { }
        }
        updateBubbleFace()
    }

    private fun scheduleNext(m: MarkerView) {
        if (!running) return
        val lo = m.point.intervalMinMs
        val hi = m.point.intervalMaxMs
        val delay = if (hi > lo) Random.nextLong(lo, hi + 1) else lo
        handler.postDelayed({ fire(m) }, delay)
    }

    private fun fire(m: MarkerView) {
        if (!running || !markers.contains(m)) return
        val service = TapAccessibilityService.instance
        if (service == null) {
            toast("Accessibility service stopped — run paused")
            stopRun()
            return
        }
        val c = markerCentre(m)
        val r = m.effectiveRandomRadius
        var x = c.x.toFloat()
        var y = c.y.toFloat()
        if (r > 0) {
            // Uniform random point inside the circle — finger-like.
            val rr = r * sqrt(Random.nextDouble())
            val theta = Random.nextDouble() * 2 * Math.PI
            x += (rr * Math.cos(theta)).toFloat()
            y += (rr * Math.sin(theta)).toFloat()
        }
        service.tap(x, y)
        m.point.clickCount++
        m.flash()
        scheduleNext(m)
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
