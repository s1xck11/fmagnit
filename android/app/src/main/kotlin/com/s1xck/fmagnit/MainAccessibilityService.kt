package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Две задачи:
 * 1) Поллинг UsageStats раз в 1 сек — определение foreground-пакета
 *    (работает на EMUI 12, где getWindows() заблокирован).
 * 2) Чтение экрана Магнита: на экране "Прибыть к клиенту" достаём адрес
 *    заказа и передаём в OrderCapture (геокод + заказ-таргет).
 */
class MainAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private var scanRunnable: Runnable? = null
    private var lastEventSource: AccessibilityNodeInfo? = null
    private var lastDebugDump: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != OverlayBridge.TARGET_PACKAGE) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                try {
                    val old = lastEventSource
                    lastEventSource = e.source
                    old?.recycle()
                } catch (_: Throwable) {}
                scheduleScan()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (_: Exception) {}
        startPolling()
        refreshTargetState()
    }

    override fun onDestroy() {
        stopPolling()
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
        try { lastEventSource?.recycle() } catch (_: Throwable) {}
        lastEventSource = null
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }

    // ===================== FOREGROUND-ПОЛЛИНГ (как было) =====================

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

    // ===================== ЧТЕНИЕ ЭКРАНА ЗАКАЗА =====================

    private fun scheduleScan() {
        scanRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { scanScreen() }
        scanRunnable = r
        handler.postDelayed(r, 600)
    }

    private fun scanScreen() {
        try {
            var root: AccessibilityNodeInfo? = null
            try { root = rootInActiveWindow } catch (_: Throwable) {}

            // Фолбэк: поднимаемся от узла события к корню,
            // если rootInActiveWindow вдруг null.
            if (root == null) {
                val src = lastEventSource
                if (src != null) {
                    try { src.refresh() } catch (_: Throwable) {}
                    var n: AccessibilityNodeInfo? = src
                    var guard = 0
                    while (n != null && guard < 30) {
                        val p = try { n.parent } catch (_: Throwable) { null } ?: break
                        n = p
                        guard++
                    }
                    root = n
                }
            }
            if (root == null) return

            val texts = ArrayList<String>(64)
            collectTexts(root, texts, 0)

            if (!isCustomerScreen(texts)) return

            val address = extractAddress(texts)
            if (address == null) {
                // Дамп для отладки: увидишь в баннере "Краш" на главной.
                val dump = texts.joinToString(" | ").take(4000)
                if (dump != lastDebugDump) {
                    lastDebugDump = dump
                    CrashLogger.logMessage(applicationContext, "capture_debug", dump)
                }
                return
            }

            OrderCapture.process(applicationContext, address)
        } catch (_: Throwable) {}
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: ArrayList<String>, depth: Int) {
        if (node == null || depth > 30 || out.size > 500) return
        try {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t)
            val cd = node.contentDescription?.toString()?.trim()
            if (!cd.isNullOrEmpty() && cd != t) out.add(cd)
            for (i in 0 until node.childCount) {
                collectTexts(node.getChild(i), out, depth + 1)
            }
        } catch (_: Throwable) {}
    }

    private fun isCustomerScreen(texts: List<String>): Boolean {
        return texts.any { it == "Прибыть к клиенту" || it == "К клиенту" }
    }

    private fun extractAddress(texts: List<String>): String? {
        // Основной путь: адрес одной строкой, начинается с "Россия,"
        for (t in texts) {
            if (t.startsWith("Россия,") && t.length > 12 && t.any { it.isDigit() }) {
                return t
            }
        }
        // Запасной: текст сразу после метки "Улица, дом"
        val idx = texts.indexOfFirst { it == "Улица, дом" }
        if (idx >= 0 && idx + 1 < texts.size) {
            val t = texts[idx + 1]
            if (t.length > 8 && t.contains(",") && t.any { it.isDigit() }) {
                return t
            }
        }
        return null
    }
}