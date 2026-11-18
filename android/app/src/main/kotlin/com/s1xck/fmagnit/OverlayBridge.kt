package com.s1xck.fmagnit

object OverlayBridge {
    // Пакет Магнита, за которым следит служба (если он у тебя другой, поменяй строку)
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    @Volatile
    var isOverlayRunning: Boolean = false
        private set

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    private var stateChangeListener: (() -> Unit)? = null

    // Хендлер ручного чтения экрана: ставит AccessibilityService,
    // дёргает кнопка в оверлее.
    @Volatile
    var scanHandler: (() -> Unit)? = null

    fun setOverlayRunning(running: Boolean) {
        if (isOverlayRunning != running) {
            isOverlayRunning = running
            notifyStateChanged()
        }
    }

    fun setTargetForeground(isForeground: Boolean) {
        if (isTargetAppForeground != isForeground) {
            isTargetAppForeground = isForeground
            notifyStateChanged()
        }
    }

    fun onOverlayStateChanged(listener: () -> Unit) {
        stateChangeListener = listener
    }

    fun notifyStateChanged() {
        stateChangeListener?.invoke()
    }

    /** true = команда чтения ушла в службу спецвозможностей. */
    fun requestScreenScan(): Boolean {
        val h = scanHandler ?: return false
        h.invoke()
        return true
    }
}