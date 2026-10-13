package com.s1xck.fmagnit

/**
 * Синглтон-мост между AccessibilityService и OverlayService.
 * Accessibility знает, какое приложение на переднем плане.
 * Overlay знает, показывать ли окно.
 * Они не могут общаться напрямую — общаются через этот объект.
 */
object OverlayBridge {
    const val TARGET_PACKAGE = "com.magnit.delivery.courier"

    @Volatile
    var isTargetAppForeground: Boolean = false
        private set

    @Volatile
    var isOverlayServiceRunning: Boolean = false

    private var overlayListener: (() -> Unit)? = null
    private var accessibilityListener: (() -> Unit)? = null

    fun setTargetForeground(value: Boolean) {
        if (isTargetAppForeground == value) return
        isTargetAppForeground = value
        overlayListener?.invoke()
    }

    fun setOverlayRunning(value: Boolean) {
        isOverlayServiceRunning = value
        accessibilityListener?.invoke()
    }

    fun onOverlayStateChanged(listener: () -> Unit) {
        overlayListener = listener
    }

    fun onAccessibilityStateChanged(listener: () -> Unit) {
        accessibilityListener = listener
    }
}