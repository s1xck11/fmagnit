package com.s1xck.fmagnit

/**
 * Синглтон-мост между AccessibilityService и OverlayService.
 */
object OverlayBridge {
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

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