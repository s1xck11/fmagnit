package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

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

        OverlayBridge.setTargetForeground(isTarget)
    }
}