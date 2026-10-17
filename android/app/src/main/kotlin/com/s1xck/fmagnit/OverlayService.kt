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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale

class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        // ==== ЦВЕТА ====
        private const val COLOR_HEADER_BG = 0xFF1E1E1E.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DOT_ACTIVE = 0xFF009E27.toInt()
        private const val COLOR_DOT_IDLE = 0xFFE30613.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_ACCENT = 0xFFE30613.toInt()
        private const val COLOR_INACTIVE = 0xFF2A2A2A.toInt()

        private const val HEADER_HEIGHT_DP = 56
        private const val TITLE_FONT_SP = 20f
        private const val STATUS_FONT_SP = 12f
        private const val DOT_SIZE_SP = 14f

        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"
        const val ACTION_ORDER_TARGET = "com.s1xck.fmagnit.OVERLAY_ORDER_TARGET"
        const val ACTION_ORDER_TARGET_CLEAR = "com.s1xck.fmagnit.OVERLAY_ORDER_CLEAR"
        const val ACTION_ORDER_START_MOCK = "com.s1xck.fmagnit.ORDER_START_MOCK"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"
        const val EXTRA_ORDER_LAT = "order_lat"
        const val EXTRA_ORDER_LON = "order_lon"

        private const val TILE_CACHE_BYTES = 100L * 1024L * 1024L

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

    private var fullMap: MapView? = null
    private var fullMapWrapper: View? = null
    private var fullMapLayoutParams: WindowManager.LayoutParams? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
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

    private fun initOsmdroid() {
        try {
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = packageName
            val base = File(cacheDir, "osmdroid")
            if (!base.exists()) base.mkdirs()
            cfg.osmdroidBasePath = base
            val tiles = File(base, "tiles")
            if (!tiles.exists()) tiles.mkdirs()
            cfg.osmdroidTileCache = tiles
            try {
                cfg.tileFileSystemCacheMaxBytes = TILE_CACHE_BYTES
                cfg.tileFileSystemCacheTrimBytes = TILE_CACHE_BYTES / 2
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
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
        closeFullMap()
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
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
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
        val openIntent = Intent(this, com.google.android.apps.maps.MapsActivity::class.java).apply {
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
            background = roundedBg(COLOR_HEADER_BG, 0)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(16), 0)
            background = roundedBg(COLOR_HEADER_BG, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(HEADER_HEIGHT_DP)
            )
        }

        dotView = TextView(ctx).apply {
            text = "●"
            setTextColor(COLOR_DOT_IDLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DOT_SIZE_SP)
            setPadding(0, 0, dp(12), 0)
        }

        val title = TextView(ctx).apply {
            text = "fmagnit"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_FONT_SP)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        activeLabel = TextView(ctx).apply {
            text = currentActiveNameOrPlaceholder()
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_FONT_SP)
            setPadding(0, 0, dp(12), 0)
        }

        arrowView = TextView(ctx).apply {
            text = "▼"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        header.addView(dotView)
        header.addView(title)
        header.addView(activeLabel)
        header.addView(arrowView)
        root.addView(header)

        orderContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(16))
            background = roundedBg(COLOR_HEADER_BG, 0)
            visibility = View.GONE
        }
        root.addView(orderContainer)

        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(16))
            background = roundedBg(COLOR_HEADER_BG, 0)
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
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
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
                        val newY = (initialY + dy.toInt()).coerceAtLeast(0)
                        layoutParams?.y = newY
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

    private fun createMapView(): MapView {
        val ctx = this
        val map = MapView(ctx)
        try {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setUseDataConnection(true)
            map.setMultiTouchControls(true)
            map.controller.setZoom(17.0)
            map.invalidate()
        } catch (_: Exception) {}
        return map
    }

    private fun addMarker(map: MapView, lat: Double, lon: Double) {
        try {
            map.overlays.clear()
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Заказ"
            map.overlays.add(marker)
            map.controller.setCenter(GeoPoint(lat, lon))
            map.controller.setZoom(17.0)
            map.invalidate()
        } catch (_: Exception) {}
    }

    private fun rebuildOrderBlock() {
        val ctx = this
        val c = orderContainer ?: return
        c.removeAllViews()

        val order = OrderTargetStore.load(this)
        if (order == null) {
            c.visibility = View.GONE
            return
        }
        c.visibility = View.VISIBLE

        val (lat, lon) = order

        c.addView(TextView(ctx).apply {
            text = "Заказ клиента"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })

        c.addView(TextView(ctx).apply {
            text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(10))
        })

        c.addView(TextView(ctx).apply {
            text = "ОТКРЫТЬ КАРТУ"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBg(COLOR_INACTIVE, 12)
            setOnClickListener { openFullMap(lat, lon) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val goBtn = TextView(ctx).apply {
            text = "ПЕРЕЙТИ"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(COLOR_ACCENT, 12)
            setOnClickListener {
                val ok = MockManager.start(this@OverlayService, lat, lon, 5.0, 0.0, 1000L)
                isRunning = ok
                OverlayPrefs.saveRunning(this@OverlayService, ok)
                rebuildContent()
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.rightMargin = dp(6) }
        }

        val closeBtn = TextView(ctx).apply {
            text = "ЗАКРЫТЬ"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(COLOR_INACTIVE, 12)
            setOnClickListener {
                OrderTargetStore.clear(this@OverlayService)
                expanded = false
                rebuildContent()
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(6) }
        }

        row.addView(goBtn)
        row.addView(closeBtn)
        c.addView(row)
    }

    private fun openFullMap(lat: Double, lon: Double) {
        closeFullMap()

        expanded = false
        orderContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        arrowView?.text = "▼"

        val ctx = this

        fullMap = createMapView()
        fullMap?.let { addMarker(it, lat, lon) }

        val container = FrameLayout(ctx)
        fullMap?.let {
            container.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        val closeBtn = TextView(ctx).apply {
            text = "×"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            background = roundedBg(COLOR_ACCENT, 22)
            setPadding(dp(14), dp(4), dp(14), dp(10))
            setOnClickListener { closeFullMap() }
        }

        val closeLp = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12)
            rightMargin = dp(12)
        }
        container.addView(closeBtn, closeLp)

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_HEADER_BG, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(container, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.9).toInt()
        val h = (dm.heightPixels * 0.85).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        fullMapLayoutParams = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(wrapper, fullMapLayoutParams)
            fullMapWrapper = wrapper
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка карты: ${e.message}", Toast.LENGTH_LONG).show()
            closeFullMap()
        }
    }

    private fun closeFullMap() {
        try { fullMapWrapper?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        fullMapWrapper = null
        fullMapLayoutParams = null
        try { fullMap?.onDetach() } catch (_: Exception) {}
        fullMap = null
    }

    private fun rebuildContent() {
        val ctx = this
        val container = contentContainer ?: return
        container.removeAllViews()

        val hasOrder = OrderTargetStore.load(this) != null
        rebuildOrderBlock()

        // Обновляем актуальное состояние MockManager
        isRunning = MockManager.isRunning()
        applyDotColor()

        if (expanded) {
            orderContainer?.visibility = if (hasOrder) View.VISIBLE else View.GONE
            container.visibility = View.VISIBLE
            arrowView?.text = "▼"
            activeLabel?.text = currentActiveNameOrPlaceholder()

            container.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
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
                            OverlayPrefs.saveActiveId(this@OverlayService, id)
                            activeLabel?.text = name
                            rebuildContent()
                        }
                    }
                    container.addView(row)
                }
            }

            val toggleBtn = TextView(ctx).apply {
                text = if (isRunning) "СТОП" else "СТАРТ"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = roundedBg(
                    if (isRunning) COLOR_INACTIVE else COLOR_ACCENT,
                    12
                )
                setOnClickListener {
                    if (isRunning) {
                        MockManager.stop(this@OverlayService)
                        isRunning = false
                        OverlayPrefs.saveRunning(this@OverlayService, false)
                    } else {
                        val id = currentActiveId
                        if (id != null) {
                            val point = parsePointsFull(currentPointsJson)
                                .firstOrNull { it.first == id }
                            if (point != null) {
                                val ok = MockManager.start(
                                    this@OverlayService,
                                    point.second, point.third,
                                    point.fourth, 0.0, 1000L
                                )
                                isRunning = ok
                                OverlayPrefs.saveRunning(this@OverlayService, ok)
                            }
                        }
                    }
                    rebuildContent()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(10) }
            }
            container.addView(toggleBtn)

            // ==== ВОССТАНОВИТЬ GPS ====
            val restoreBtn = TextView(ctx).apply {
                text = "ВОССТАНОВИТЬ GPS"
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = roundedBg(COLOR_INACTIVE, 12)
                setOnClickListener {
                    MockManager.forceReset(this@OverlayService)
                    isRunning = false
                    OverlayPrefs.saveRunning(this@OverlayService, false)
                    Toast.makeText(
                        this@OverlayService,
                        "GPS восстановлен. Подожди 5-10 секунд.",
                        Toast.LENGTH_LONG
                    ).show()
                    rebuildContent()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
            }
            container.addView(restoreBtn)
        } else {
            orderContainer?.visibility = if (hasOrder) View.VISIBLE else View.GONE
            container.visibility = View.GONE
            arrowView?.text = "▼"
            activeLabel?.text = currentActiveNameOrPlaceholder()
        }
    }

    /** id, name, lat, lon, accuracy для старта mock */
    private fun parsePointsFull(json: String): List<Quadruple> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Quadruple>(arr.length())
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                val lat = obj.optDouble("latitude", 0.0)
                val lon = obj.optDouble("longitude", 0.0)
                val acc = obj.optDouble("accuracy", 3.0)
                out.add(Quadruple(id, lat, lon, acc))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    data class Quadruple(
        val first: String,   // id
        val second: Double,  // lat
        val third: Double,   // lon
        val fourth: Double   // accuracy
    )

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
        val usageOk = ForegroundDetector.hasUsageAccess(this)
        if (!usageOk) {
            v.visibility = View.VISIBLE
            return
        }
        v.visibility = if (OverlayBridge.isTargetAppForeground) View.VISIBLE else View.GONE
    }
}

const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"