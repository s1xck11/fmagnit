package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

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
                handler.postDelayed(this, 500)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 500)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun refreshTargetState() {
        val target = OverlayBridge.TARGET_PACKAGE
        var isTarget = false

        // Способ 1: через список всех окон
        try {
            val windows: List<AccessibilityWindowInfo>? = windows
            if (windows != null && windows.isNotEmpty()) {
                for (w in windows) {
                    val pkg = packageNameOfWindow(w)
                    if (pkg == target) {
                        isTarget = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Способ 2: если способ 1 не дал — через корневое окно
        if (!isTarget) {
            try {
                val active = rootInActiveWindow
                val pkg = active?.packageName?.toString()
                if (pkg == target) isTarget = true
            } catch (_: Exception) {
            }
        }

        // Способ 3: через события (последний)
        if (!isTarget) {
            try {
                val focused = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                val pkg = focused?.packageName?.toString()
                if (pkg == target) isTarget = true
            } catch (_: Exception) {
            }
        }

        OverlayBridge.setTargetForeground(isTarget)
    }

    private fun packageNameOfWindow(w: AccessibilityWindowInfo): String? {
        return try {
            w.root?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
    }
}