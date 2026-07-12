package com.tesla.screenflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务触控模拟器 — 通过 [dispatchGesture] 将车机触控回传事件映射到手机屏幕。
 */
class TouchSimulator : AccessibilityService() {

    companion object {
        private const val TAG = "TeslaScreenFlow::TouchSimulator"

        @Volatile
        var instance: TouchSimulator? = null
            private set

        fun performTouch(action: String, normalizedX: Float, normalizedY: Float) {
            instance?.handleTouch(action, normalizedX, normalizedY)
                ?: Log.w(TAG, "无障碍服务未启用，无法执行触控")
        }

        fun performClick(normalizedX: Float, normalizedY: Float) {
            instance?.performClickInternal(normalizedX, normalizedY)
                ?: Log.w(TAG, "无障碍服务未启用，无法执行点击")
        }

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍触控服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍触控服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun handleTouch(action: String, normalizedX: Float, normalizedY: Float) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val x = (normalizedX * w).coerceIn(0f, w)
        val y = (normalizedY * h).coerceIn(0f, h)

        Log.d(TAG, "触控: $action ($normalizedX, $normalizedY) -> ($x, $y)")

        when (action.lowercase()) {
            "down" -> dispatchPressGesture(x, y, 100, true)
            "move" -> dispatchPressGesture(x, y, 16, true)
            "up"   -> dispatchPressGesture(x, y, 1, false)
            "click" -> performClickInternal(normalizedX, normalizedY)
            else  -> Log.w(TAG, "未知触控动作: $action")
        }
    }

    private fun dispatchPressGesture(x: Float, y: Float, durationMs: Long, willContinue: Boolean) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(gesture, null, null)
        if (ok) Log.d(TAG, "手势派发成功: ($x, $y) ${durationMs}ms")
        else Log.e(TAG, "手势派发失败: ($x, $y)")
    }

    private fun performClickInternal(normalizedX: Float, normalizedY: Float) {
        val dm = resources.displayMetrics
        val x = (normalizedX * dm.widthPixels.toFloat()).coerceIn(0f, dm.widthPixels.toFloat())
        val y = (normalizedY * dm.heightPixels.toFloat()).coerceIn(0f, dm.heightPixels.toFloat())

        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100L, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(gesture, null, null)
        if (ok) Log.d(TAG, "点击成功: ($normalizedX, $normalizedY)")
        else Log.e(TAG, "点击失败: ($normalizedX, $normalizedY)")
    }
}
