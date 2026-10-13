package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Основной детектор переднего плана.
 * 1. Пробует UsageStatsManager — работает на EMUI, MIUI и т.п.
 * 2. Если Usage Access не выдан — падает на Accessibility.getWindows().
 * 3. Если оба молчат — оставляем предыдущее состояние (не мигаем).
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

        // 1. UsageStats — основной путь
        val usagePkg = ForegroundDetector.getForegroundPackage(this)
        if (usagePkg != null) {
            OverlayBridge.setTargetForeground(usagePkg == target)
            return
        }

        // 2. Accessibility windows — резервный
        if (checkWindows(target) || checkRootActive(target) || checkFocus(target)) {
            OverlayBridge.setTargetForeground(true)
            return
        }

        // 3. Не смогли определить — оставляем как было, не трогаем флаг.
        //    (Никаких false — чтобы не мигало.)
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
            rootInActiveWindow?.packageName?.toString() == target
        } catch (_: Exception) {
            false
        }
    }

    private fun checkFocus(target: String): Boolean {
        return try {
            findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?.packageName?.toString() == target
        } catch (_: Exception) {
            false
        }
    }
}