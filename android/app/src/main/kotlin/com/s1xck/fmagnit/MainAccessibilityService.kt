package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Слушает смену активного окна. Использует getWindows() для стабильного
 * определения переднего плана — реагирует на каждое событие, но проверяет
 * актуальный список окон, а не последнее событие. Это убивает мигание.
 */
class MainAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                refreshTargetState()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshTargetState()
    }

    override fun onDestroy() {
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }

    private fun refreshTargetState() {
        val target = OverlayBridge.TARGET_PACKAGE
        var isTarget = false

        try {
            val windows = windows
            if (windows != null) {
                for (w in windows) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString() ?: continue
                    if (pkg == target) {
                        isTarget = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Если getWindows() не дал результата — пробуем по последнему событию
        if (!isTarget) {
            try {
                val recent = windows
                if (recent == null || recent.isEmpty()) {
                    // Оставляем предыдущее состояние — не мигаем
                    return
                }
            } catch (_: Exception) {
                return
            }
        }

        OverlayBridge.setTargetForeground(isTarget)
    }
}