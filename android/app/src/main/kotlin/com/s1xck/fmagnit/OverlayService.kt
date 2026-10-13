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

class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        private const val COLOR_BG = 0xEE0A0A0A.toInt()
        private const val COLOR_ACCENT = 0xFFE30613.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_INACTIVE = 0xFF2A2A2A.toInt()

        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"

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
    private var headerContainer: LinearLayout? = null
    private var arrowView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        OverlayBridge.setOverlayRunning(true)
        OverlayBridge.onOverlayStateChanged {
            handler.post { applyVisibility() }
        }

        buildOverlayView()

        // Принудительно показать сразу — на случай если Accessibility уже видит Магнит
        applyVisibility()
        handler.postDelayed({ applyVisibility() }, 800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE_POINTS -> {
                intent.getStringExtra(EXTRA_POINTS_JSON)?.let { currentPointsJson = it }
                intent.getStringExtra(EXTRA_ACTIVE_ID)?.let { currentActiveId = it }
                handler.post { rebuildContent() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { rootView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        rootView = null
        OverlayBridge.setOverlayRunning(false)
        OverlayBridge.onOverlayStateChanged {}
        super.onDestroy()
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

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

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
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBg(COLOR_BG, 16)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleExpanded() }
        }
        headerContainer = header

        val dot = TextView(ctx).apply {
            text = "●"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, dp(8), 0)
        }

        val title = TextView(ctx).apply {
            text = "fmagnit"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        activeLabel = TextView(ctx).apply {
            text = "не выбрано"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, dp(12), 0)
        }

        arrowView = TextView(ctx).apply {
            text = "▲"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        header.addView(dot)
        header.addView(title)
        header.addView(activeLabel)
        header.addView(arrowView)
        root.addView(header)

        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(contentContainer)

        attachDragHandler(header)

        rootView = root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val width = screenWidth() - dp(32)

        layoutParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80)
            x = 0
        }

        try {
            windowManager?.addView(root, layoutParams)
            Toast.makeText(this, "Оверлей создан", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка оверлея: ${e.message}", Toast.LENGTH_LONG).show()
        }

        rebuildContent()
        applyVisibility()
    }

    private fun attachDragHandler(view: View) {
        var initialY = 0
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams?.y ?: 0
                    initialTouchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dy) > 10) moved = true
                    if (moved) {
                        layoutParams?.y = initialY - dy.toInt()
                        try { windowManager?.updateViewLayout(rootView, layoutParams) } catch (_: Exception) {}
                    }
                    moved
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        rebuildContent()
    }

    private fun rebuildContent() {
        val ctx = this
        val container = contentContainer ?: return
        container.removeAllViews()

        container.visibility = if (expanded) View.VISIBLE else View.GONE
        arrowView?.text = if (expanded) "▼" else "▲"

        if (!expanded) return

        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.topMargin = dp(10); it.bottomMargin = dp(6) }
            setBackgroundColor(0x22FFFFFF)
        })

        val points = parsePoints(currentPointsJson)
        if (points.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "Нет точек. Добавь в fmagnit."
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, dp(6))
            })
        } else {
            points.forEach { (id, name) ->
                val isActive = id == currentActiveId
                val row = TextView(ctx).apply {
                    text = (if (isActive) "● " else "○ ") + name
                    setTextColor(if (isActive) COLOR_ACCENT else COLOR_TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener {
                        currentActiveId = id
                        activeLabel?.text = name
                        rebuildContent()
                    }
                }
                container.addView(row)
            }
        }

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val playBtn = TextView(ctx).apply {
            text = if (isRunning) "ЗАПУЩЕНО" else "СТАРТ"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(COLOR_ACCENT, 14)
            setOnClickListener {
                val id = currentActiveId
                if (id != null) {
                    isRunning = true
                    sendBroadcast(Intent(ACTION_POINT_START).apply {
                        setPackage(packageName); putExtra("id", id)
                    })
                    rebuildContent()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.rightMargin = dp(6) }
        }

        val stopBtn = TextView(ctx).apply {
            text = "СТОП"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(COLOR_INACTIVE, 14)
            setOnClickListener {
                isRunning = false
                sendBroadcast(Intent(ACTION_POINT_STOP).apply { setPackage(packageName) })
                rebuildContent()
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(6) }
        }

        buttonsRow.addView(playBtn)
        buttonsRow.addView(stopBtn)
        container.addView(buttonsRow)
    }

    private fun parsePoints(json: String): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Pair<String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "точка")
                if (id.isNotEmpty()) out.add(id to name)
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun applyVisibility() {
        val v = rootView ?: return
        v.visibility = if (OverlayBridge.isTargetAppForeground) View.VISIBLE else View.GONE
    }
}

const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"