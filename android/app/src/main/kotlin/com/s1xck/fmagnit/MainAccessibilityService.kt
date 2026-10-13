package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Определяет активное приложение через UsageStatsManager.
 * Работает на EMUI 12 (там Accessibility.getWindows() заблокирован).
 * Поллинг раз в 1 сек. Если Usage Access не выдан — оставляем состояние как есть.
 */
class MainAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Больше не полагаемся на события — только на поллинг UsageStats.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (_: Exception) {
        }
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

        // Если Usage Access не выдан — ничего не делаем, не мигаем.
        if (!ForegroundDetector.hasUsageAccess(this)) {
            return
        }

        val pkg = ForegroundDetector.getForegroundPackage(this) ?: return
        OverlayBridge.setTargetForeground(pkg == target)
    }
}