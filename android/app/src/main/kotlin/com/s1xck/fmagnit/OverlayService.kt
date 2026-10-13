package com.s1xck.fmagnit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        private const val COLOR_BG = 0xEE0A0A0A.toInt()
        private const val COLOR_ACCENT = 0xFFE30613.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_INACTIVE = 0xFF2A2A2A.toInt()
        private const val COLOR_DOT_IDLE = 0xFFE30613.toInt()
        private const val COLOR_DOT_ACTIVE = 0xFF00C853.toInt()

        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"
        const val ACTION_ORDER_TARGET = "com.s1xck.fmagnit.OVERLAY_ORDER_TARGET"
        const val ACTION_ORDER_TARGET_CLEAR = "com.s1xck.fmagnit.OVERLAY_ORDER_CLEAR"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"
        const val EXTRA_ORDER_LAT = "order_lat"
        const val EXTRA_ORDER_LON = "order_lon"

        @Volatile var currentPointsJson: String = "[]"
        @Volatile var currentActiveId: String? = null
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var expanded = false
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())

    private var contentContainer: LinearLayout? = null
    private var activeLabel: TextView? = null
    private var arrowView: TextView? = null
    private var dotView: TextView? = null

    private var orderContainer: LinearLayout? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        acquireWakeLock()

        currentPointsJson = OverlayPrefs.loadPoints(this)
        currentActiveId = OverlayPrefs.loadActiveId(this)
        isRunning = OverlayPrefs.loadRunning(this)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        OverlayBridge.setOverlayRunning(true)
        OverlayBridge.onOverlayStateChanged {
            handler.post { applyVisibility() }
        }

        buildOverlayView()
        applyVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OverlayPrefs.saveServiceOn(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_POINTS -> {
                intent.getStringExtra(EXTRA_POINTS_JSON)?.let {
                    currentPointsJson = it
                    OverlayPrefs.savePoints(this, it)
                }
                intent.getStringExtra(EXTRA_ACTIVE_ID)?.let {
                    currentActiveId = it
                    OverlayPrefs.saveActiveId(this, it)
                }
                handler.post { rebuildContent() }
            }
            ACTION_ORDER_TARGET -> {
                val lat = intent.getDoubleExtra(EXTRA_ORDER_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_ORDER_LON, Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    OrderTargetStore.save(this, lat, lon)
                    expanded = true
                    handler.post { rebuildContent() }
                }
            }
            ACTION_ORDER_TARGET_CLEAR -> {
                OrderTargetStore.clear(this)
                handler.post { rebuildContent() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayBridge.onOverlayStateChanged {}
        try { rootView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        rootView = null
        OverlayBridge.setOverlayRunning(false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "fmagnit::overlay_wakelock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "fmagnit overlay", NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("fmagnit")
            .setContentText("Оверлей активен")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        return d
    }

    private fun buildOverlayView() {
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBg(COLOR_BG, 16)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
        }

        dotView = TextView(ctx).apply {
            text = "●"
            setTextColor(COLOR_DOT_IDLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(10), 0)
        }

        val title = TextView(ctx).apply {
            text = "fmagnit"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        activeLabel = TextView(ctx).apply {
            text = currentActiveNameOrPlaceholder()
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(12), 0)
        }

        arrowView = TextView(ctx).apply {
            text = "▲"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        header.addView(dotView)
        header.addView(title)
        header.addView(activeLabel)
        header.addView(arrowView)
        root.addView(header)

        orderContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(orderContainer)

        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(contentContainer)

        attachHeaderGestures(header)

        rootView = root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            y = dp(80)
            x = 0
        }

        try {
            windowManager?.addView(root, layoutParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка оверлея: ${e.message}", Toast.LENGTH_LONG).show()
        }

        applyDotColor()
        rebuildContent()
    }

    private fun attachHeaderGestures(view: View) {
        var downTime = 0L
        var initialY = 0
        var initialTouchY = 0f
        var moved = false
        var handled = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = SystemClock.elapsedRealtime()
                    initialY = layoutParams?.y ?: 0
                    initialTouchY = event.rawY
                    moved = false
                    handled = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (handled) return@setOnTouchListener true
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dy) > 10) {
                        moved = true
                        layoutParams?.y = initialY - dy.toInt()
                        try { windowManager?.updateViewLayout(rootView, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (handled) return@setOnTouchListener true
                    val duration = SystemClock.elapsedRealtime() - downTime
                    val isTap = !moved && duration < 300
                    if (isTap) {
                        toggleExpanded()
                        handled = true
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handled = true
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        rebuildContent()
    }

    private fun currentActiveNameOrPlaceholder(): String {
        val id = currentActiveId ?: return "не выбрано"
        return parsePoints(currentPointsJson).firstOrNull { it.first == id }?.second
            ?: "не выбрано"
    }

    private fun applyDotColor() {
        dotView?.setTextColor(if (isRunning) COLOR_DOT_ACTIVE else COLOR_DOT_IDLE)
    }

    private fun rebuildOrderBlock() {
        val ctx = this
        val c = orderContainer ?: return
        c.removeAllViews()
