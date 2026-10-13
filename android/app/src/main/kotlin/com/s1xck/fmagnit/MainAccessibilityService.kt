package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Определяет, активно ли целевое приложение (Магнит.Доставка) на переднем плане.
 * Логика: три способа проверки + polling раз в 1 сек. Если не смогли определить —
 * возвращаем "true" (показываем оверлей). Это fallback: лучше показать лишний раз,
 * чем скрыть, когда реально нужно.
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

        val result = checkWindows(target)
            ?: checkRootActive(target)
            ?: checkFocus(target)

        // Fallback: если ни один способ не сработал — показываем (true).
        val isTarget = result ?: true
        OverlayBridge.setTargetForeground(isTarget)
    }

    /** null = не смогли определить, true/false = определили. */
    private fun checkWindows(target: String): Boolean? {
        return try {
            val windows: List<AccessibilityWindowInfo>? = windows
            if (windows == null || windows.isEmpty()) return null
            var sawAnyPkg = false
            for (w in windows) {
                val pkg = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
                if (pkg != null) {
                    sawAnyPkg = true
                    if (pkg == target) return true
                }
            }
            if (sawAnyPkg) false else null
        } catch (_: Exception) {
            null
        }
    }

    private fun checkRootActive(target: String): Boolean? {
        return try {
            val active = rootInActiveWindow
            val pkg = active?.packageName?.toString() ?: return null
            pkg == target
        } catch (_: Exception) {
            null
        }
    }

    private fun checkFocus(target: String): Boolean? {
        return try {
            val focused = findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
            )
            val pkg = focused?.packageName?.toString() ?: return null
            pkg == target
        } catch (_: Exception) {
            null
        }
    }
}