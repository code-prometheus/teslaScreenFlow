package com.tesla.screenflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchSimulator : AccessibilityService() {

    companion object {
        private const val TAG = "TeslaScreenFlow::Touch"

        @Volatile var instance: TouchSimulator? = null
            private set

        fun performTouch(action: String, normalizedX: Float, normalizedY: Float) {
            instance?.handleTouch(action, normalizedX, normalizedY)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "触控服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handleTouch(action: String, normalizedX: Float, normalizedY: Float) {
        val dm = resources.displayMetrics
        val x = (normalizedX * dm.widthPixels.toFloat()).coerceIn(0f, dm.widthPixels.toFloat())
        val y = (normalizedY * dm.heightPixels.toFloat()).coerceIn(0f, dm.heightPixels.toFloat())

        val durationMs = when (action.lowercase()) {
            "down" -> 100L; "move" -> 16L; "up" -> 1L; else -> return
        }
        val willContinue = action.lowercase() != "up"

        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
