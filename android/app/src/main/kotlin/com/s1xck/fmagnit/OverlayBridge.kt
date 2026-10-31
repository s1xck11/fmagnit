package com.s1xck.fmagnit

object OverlayBridge {
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    @Volatile
    var isOverlayRunning: Boolean = false

    private var overlayListener: (() -> Unit)? = null

    fun setTargetForeground(value: Boolean) {
        if (isTargetAppForeground == value) return
        isTargetAppForeground = value
        overlayListener?.invoke()
    }

    fun setOverlayRunning(value: Boolean) {
        isOverlayRunning = value
    }

    fun onOverlayStateChanged(listener: () -> Unit) {
        overlayListener = listener
    }
}