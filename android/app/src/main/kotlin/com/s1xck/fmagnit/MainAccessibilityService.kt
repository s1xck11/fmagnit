package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Слушает смену активного окна. Когда на переднем плане
 * com.magnit.delivery.courier — включает флаг в OverlayBridge.
 * OverlayService по этому флагу показывает/скрывает окно.
 */
class MainAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return

        // Игнорируем системные окна и свой собственный overlay-пакет
        if (pkg == packageName) return
        if (pkg.startsWith("com.android.systemui")) return

        val isTarget = pkg == OverlayBridge.TARGET_PACKAGE
        OverlayBridge.setTargetForeground(isTarget)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayBridge.setTargetForeground(false)
    }

    override fun onDestroy() {
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }
}