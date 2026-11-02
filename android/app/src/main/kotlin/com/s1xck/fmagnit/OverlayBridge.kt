package com.s1xck.fmagnit

object OverlayBridge {

    @Volatile
    var isOverlayRunning: Boolean = false

    @Volatile
    var isTargetAppForeground: Boolean = false

    private var stateChangeListener: (() -> Unit)? = null

    fun onOverlayStateChanged(listener: () -> Unit) {
        stateChangeListener = listener
    }

    fun notifyStateChanged() {
        stateChangeListener?.invoke()
    }
}