package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Пока не используется для видимости оверлея. Оставлен для будущего фильтра
 * по активному приложению. Сейчас просто пишет флаг в OverlayBridge.
 */
class MainAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        OverlayBridge.setTargetForeground(pkg == OverlayBridge.TARGET_PACKAGE)
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onDestroy() {
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }
}