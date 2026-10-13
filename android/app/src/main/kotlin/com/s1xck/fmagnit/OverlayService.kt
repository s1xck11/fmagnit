package com.s1xck.fmagnit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

/**
 * Foreground Service с плавающим окном внизу экрана.
 * Показывается только когда OverlayBridge.isTargetAppForeground == true.
 * Управляется из Dart через MethodChannel в MainActivity.
 */
class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        // Цвета и размеры
        private const val COLOR_BG = 0xF20D0D0D.toInt()
        private const val COLOR_ACCENT = 0xFFE53935.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF8A8A8A.toInt()

        // Команды извне
        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"

        // Текущее состояние — статикой, чтобы MainActivity мог читать
        @Volatile
        var currentPointsJson: String = "[]"

        @Volatile
        var currentActiveId: String? = null
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var expanded = false
    private var layoutParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())

    // Ссылки на элементы
    private var contentContainer: LinearLayout? = null
    private var activeLabel: TextView? = null
    private var playButton: TextView? = null
    private var stopButton: TextView? = null
    private var toggleButton: TextView? = null

    // callback для отправки событий в Dart (пока не используем, но оставим точку расширения)
    private var onPointSelected: ((String) -> Unit)? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_POINTS -> {
                val json = intent.getStringExtra(EXTRA_POINTS_JSON)
                if (json != null) {
                    currentPointsJson = json
                }
                val activeId = intent.getStringExtra(EXTRA_ACTIVE_ID)
                if (activeId != null) currentActiveId = activeId
                handler.post { rebuildContent() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        rootView = null
        OverlayBridge.setOverlayRunning(false)
        OverlayBridge.onOverlayStateChanged {}
        super.onDestroy()
    }

    // ---------- Уведомление ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "fmagnit overlay",
                    NotificationManager.IMPORTANCE_MIN
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
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("fmagnit")
            .setContentText("Оверлей активен")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------- Вёрстка оверлея ----------

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        return d
    }

    private fun buildOverlayView() {
        val ctx = this

        // Корневой контейнер — вертикальный, снизу над навбаром
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBg(COLOR_BG, 16)
            elevation = dp(8).toFloat()
        }

        // Верхняя строка — заголовок + кнопка свернуть/развернуть
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(ctx).apply {
            text = "fmagnit"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        activeLabel = TextView(ctx).apply {
            text = "точка не выбрана"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        toggleButton = TextView(ctx).apply {
            text = "▲"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener {
                expanded = !expanded
                text = if (expanded) "▼" else "▲"
                rebuildContent()
            }
        }

        header.addView(title)
        header.addView(activeLabel)
        header.addView(toggleButton)
        root.addView(header)

        // Контейнер содержимого — список точек, кнопки пуск/стоп
        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(contentContainer)

        // Перетаскивание по экрану
        attachDragHandler(root)

        rootView = root

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // над навбаром: отступ снизу, чтобы не перекрывать нижнюю панель
            y = dp(72)
            x = 0
        }

        try {
            windowManager?.addView(root, layoutParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось показать оверлей: ${e.message}", Toast.LENGTH_LONG).show()
        }

        rebuildContent()
        applyVisibility()
    }

    private fun attachDragHandler(view: View) {
        var initialY = 0
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams?.y ?: 0
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - initialTouchY
                    layoutParams?.y = initialY - dy.toInt()
                    try {
                        windowManager?.updateViewLayout(rootView, layoutParams)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun rebuildContent() {
        val ctx = this
        val container = contentContainer ?: return

        container.removeAllViews()

        // Разделитель
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.topMargin = dp(8) }
            setBackgroundColor(0x33FFFFFF)
        })

        // Точки
        val points = parsePoints(currentPointsJson)
        if (points.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "нет точек — добавь в fmagnit"
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(4))
            }
            container.addView(empty)
        } else {
            points.forEach { (id, name) ->
                val isActive = id == currentActiveId
                val row = TextView(ctx).apply {
                    text = (if (isActive) "● " else "○ ") + name
                    setTextColor(if (isActive) COLOR_ACCENT else COLOR_TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        currentActiveId = id
                        activeLabel?.text = name
                        rebuildContent()
                        onPointSelected?.invoke(id)
                    }
                }
                container.addView(row)
            }
        }

        // Кнопки управления
        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        playButton = TextView(ctx).apply {
            text = "СТАРТ"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(COLOR_ACCENT, 12)
            setOnClickListener {
                val id = currentActiveId
                if (id != null) {
                    val intent = Intent(ACTION_POINT_START).setPackage(packageName)
                    intent.putExtra("id", id)
                    sendBroadcast(intent)
                    activeLabel?.text = "запущено"
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.rightMargin = dp(6) }
        }

        stopButton = TextView(ctx).apply {
            text = "СТОП"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(0xFF333333.toInt(), 12)
            setOnClickListener {
                val intent = Intent(ACTION_POINT_STOP).setPackage(packageName)
                sendBroadcast(intent)
                activeLabel?.text = "остановлено"
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(6) }
        }

        buttonsRow.addView(playButton)
        buttonsRow.addView(stopButton)
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applyVisibility() {
        val v = rootView ?: return
        val shouldShow = OverlayBridge.isTargetAppForeground
        v.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }
}

// Метки broadcast — вынесены вне класса, чтобы их видел MainActivity
const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"