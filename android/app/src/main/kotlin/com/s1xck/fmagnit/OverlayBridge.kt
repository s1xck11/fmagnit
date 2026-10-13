package com.s1xck.fmagnit

object OverlayBridge {
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    // По умолчанию скрыто. Показываем только когда детектор подтвердит Магнит.
    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    @Volatile
    var isOverlayServiceRunning: Boolean = false

    private var overlayListener: (() -> Unit)? = null

    fun setTargetForeground(value: Boolean) {
        if (isTargetAppForeground == value) return
        isTargetAppForeground = value
        overlayListener?.invoke()
    }

    fun setOverlayRunning(value: Boolean) {
        isOverlayServiceRunning = value
    }

    fun onOverlayStateChanged(listener: () -> Unit) {
        overlayListener = listener
    }
}