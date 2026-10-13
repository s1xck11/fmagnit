package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Определяет, активно ли целевое приложение.
 * Если НЕ смогли определить — оставляем СКРЫТО (false).
 * Показываем оверлей только при уверенности, что Магнит активен.
 */
class MainAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        refreshTargetState()
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        startPolling()
        refreshTargetState()
    }

    override fun onDestroy() {
        stopPolling()
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }

    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                refreshTargetState()
                handler.postDelayed(this, 1000)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 1000)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun refreshTargetState() {
        val target = OverlayBridge.TARGET_PACKAGE

        val isTarget = checkWindows(target)
            || checkRootActive(target)
            || checkFocus(target)

        OverlayBridge.setTargetForeground(isTarget)
    }

    private fun checkWindows(target: String): Boolean {
        return try {
            val windows: List<AccessibilityWindowInfo>? = windows
            if (windows == null || windows.isEmpty()) return false
            for (w in windows) {
                val pkg = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
                if (pkg == target) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun checkRootActive(target: String): Boolean {
        return try {
            val active = rootInActiveWindow
            active?.packageName?.toString() == target
        } catch (_: Exception) {
            false
        }
    }

    private fun checkFocus(target: String): Boolean {
        return try {
            val focused = findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
            )
            focused?.packageName?.toString() == target
        } catch (_: Exception) {
            false
        }
    }
}