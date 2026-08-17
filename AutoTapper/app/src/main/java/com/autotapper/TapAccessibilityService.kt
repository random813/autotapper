package com.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * The only sanctioned way one app can dispatch taps into another app: an
 * AccessibilityService with canPerformGestures. The user enables it once in
 * Settings -> Accessibility -> AutoTapper.
 *
 * This service does not read screen content (canRetrieveWindowContent=false);
 * it only dispatches the gestures the user configured.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Dispatch a tap (or long-press) at absolute screen coordinates.
     * @param durationMs 40ms reads as a quick human tap; longer = long-press.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 40L, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, null)
    }
}
