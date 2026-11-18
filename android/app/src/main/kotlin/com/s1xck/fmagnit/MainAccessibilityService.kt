package com.s1xck.fmagnit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Две задачи:
 * 1) Поллинг UsageStats раз в 1 сек — определение foreground-пакета
 *    (работает на EMUI 12, где getWindows() заблокирован).
 * 2) РУЧНОЕ чтение экрана Магнита по кнопке из оверлея:
 *    собираем тексты -> ищем адрес -> OrderCapture (геокод + таргет).
 *    При неудаче — дамп в CrashLogger (баннер "Краш" на главной).
 *
 * Автоперехват выключен: читаем только по кнопке.
 */
class MainAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var lastEventSource: AccessibilityNodeInfo? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != OverlayBridge.TARGET_PACKAGE) return
        // Только запоминаем источник события как фолбэк-корень.
        // Никакого автоскана.
        try {
            val old = lastEventSource
            lastEventSource = e.source
            old?.recycle()
        } catch (_: Throwable) {}
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

        OverlayBridge.scanHandler = { handler.post { scanScreen() } }

        startPolling()
        refreshTargetState()
    }

    override fun onDestroy() {
        stopPolling()
        OverlayBridge.scanHandler = null
        try { lastEventSource?.recycle() } catch (_: Throwable) {}
        lastEventSource = null
        OverlayBridge.setTargetForeground(false)
        super.onDestroy()
    }

    // ===================== FOREGROUND-ПОЛЛИНГ =====================

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

    // ===================== РУЧНОЕ ЧТЕНИЕ ЭКРАНА =====================

    private fun scanScreen() {
        val sb = StringBuilder()
        try {
            val roots = ArrayList<AccessibilityNodeInfo>()

            try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Throwable) {}
            sb.append("rootInActiveWindow=").append(roots.size).append('\n')

            try {
                var winCount = 0
                for (w in windows) {
                    winCount++
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val r = try { w.root } catch (_: Throwable) { null }
                        if (r != null) roots.add(r)
                    }
                }
                sb.append("windows=").append(winCount).append('\n')
            } catch (e: Throwable) {
                sb.append("windows_error=").append(e.toString()).append('\n')
            }

            if (roots.isEmpty()) {
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
                    if (n != null) roots.add(n)
                }
                sb.append("eventSourceFallback=").append(roots.size).append('\n')
            }

            if (roots.isEmpty()) {
                finishDebug(
                    sb.toString(),
                    "Не вижу экран. Выключи и включи fmagnit в спецвозможностях."
                )
                return
            }

            val texts = ArrayList<String>(128)
            val classes = ArrayList<String>(64)
            for (r in roots) collect(r, texts, classes, 0)

            sb.append("texts=").append(texts.size).append('\n')
            sb.append("classes=").append(classes.distinct().take(12).joinToString(",")).append('\n')

            if (texts.isEmpty()) {
                finishDebug(
                    sb.toString(),
                    "Экран виден, но текстов нет. Дамп в баннере Краш."
                )
                return
            }

            val address = extractAddress(texts)
            if (address == null) {
                sb.append(texts.joinToString(" | ").take(3500))
                finishDebug(sb.toString(), "Адрес не распознан. Дамп в баннере Краш.")
                return
            }

            Toast.makeText(applicationContext, "Адрес: $address", Toast.LENGTH_LONG).show()
            OrderCapture.process(applicationContext, address, force = true)
        } catch (e: Throwable) {
            finishDebug(sb.toString() + "\n" + e.toString(), "Ошибка чтения: ${e.message}")
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        texts: ArrayList<String>,
        classes: ArrayList<String>,
        depth: Int
    ) {
        if (node == null || depth > 30 || texts.size > 500) return
        try {
            val cn = node.className?.toString() ?: ""
            if (cn.isNotEmpty() && classes.size < 200) classes.add(cn)
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) texts.add(t)
            val cd = node.contentDescription?.toString()?.trim()
            if (!cd.isNullOrEmpty() && cd != t) texts.add(cd)
            for (i in 0 until node.childCount) {
                collect(node.getChild(i), texts, classes, depth + 1)
            }
        } catch (_: Throwable) {}
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

    private fun finishDebug(dump: String, toast: String) {
        try {
            CrashLogger.logMessage(applicationContext, "capture_debug", dump)
        } catch (_: Throwable) {}
        Toast.makeText(applicationContext, toast, Toast.LENGTH_LONG).show()
    }
}