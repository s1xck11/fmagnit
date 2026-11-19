package com.s1xck.fmagnit

object OverlayBridge {
    // Пакет Магнита, за которым следит служба
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    @Volatile
    var isOverlayRunning: Boolean = false
        private set

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    private var stateChangeListener: (() -> Unit)? = null

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
}