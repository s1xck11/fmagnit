package com.s1xck.fmagnit

object OverlayBridge {
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    // По умолчанию — показываем. Если детектор уверенно скажет «не Магнит» — скроет.
    // Это защита от «оверлей не виден вообще» на EMUI.
    @Volatile
    var isTargetAppForeground: Boolean = true
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