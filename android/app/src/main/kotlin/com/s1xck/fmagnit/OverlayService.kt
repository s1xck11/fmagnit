package com.s1xck.fmagnit

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView as OsmMapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import com.yandex.mapkit.mapview.MapView as YandexMapView
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Семантические токены тёмной темы интерфейса:
 * bg #121212, surface #202020, border #2E2E2E, fg #FFFFFF,
 * muted #9E9E9E, accent #E30613, success #00C853.
 * Touch-targets 48dp, зазоры 8dp, guard двойных тапов 400 мс.
 * Карта: MapKit (night mode) при наличии ключа, иначе OSM fallback.
 * Нижние углы карты свободны под лого Яндекса (тап ведёт на сайт Яндекса).
 */
class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        private const val COLOR_BG = 0xF2121212.toInt()
        private const val COLOR_SURFACE = 0xFF202020.toInt()
        private const val COLOR_PILL = 0xF2141414.toInt()
        private const val COLOR_BORDER = 0xFF2E2E2E.toInt()
        private const val COLOR_FG = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_ACCENT = 0xFFE30613.toInt()
        private const val COLOR_SUCCESS = 0xFF00C853.toInt()
        private const val COLOR_INACTIVE = 0xFF2A2A2A.toInt()
        private const val COLOR_MARKER_BG = 0xFFE30613.toInt()
        private const val COLOR_PLATE_BG = 0xFFFFFFFF.toInt()
        private const val COLOR_PLATE_TEXT = 0xFF1E1E1E.toInt()
        private const val COLOR_MY_LOCATION = 0xFF2196F3.toInt()

        private const val HEADER_HEIGHT_DP = 48
        private const val TOUCH_DP = 48
        private const val GAP_DP = 8
        private const val CLICK_GUARD_MS = 400L

        private const val DOOR_SEARCH_RADIUS_M = 50.0

        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"
        const val ACTION_UPDATE_DOOR_CODES = "com.s1xck.fmagnit.OVERLAY_UPDATE_DOOR_CODES"
        const val ACTION_ORDER_TARGET = "com.s1xck.fmagnit.OVERLAY_ORDER_TARGET"
        const val ACTION_ORDER_TARGET_CLEAR = "com.s1xck.fmagnit.OVERLAY_ORDER_CLEAR"
        const val ACTION_SET_HEADER_WIDTH = "com.s1xck.fmagnit.OVERLAY_SET_HEADER_WIDTH"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"
        const val EXTRA_DOOR_CODES_JSON = "door_codes_json"
        const val EXTRA_ORDER_LAT = "order_lat"
        const val EXTRA_ORDER_LON = "order_lon"
        const val EXTRA_HEADER_WIDTH_PCT = "header_width_pct"

        private const val TILE_CACHE_BYTES = 100L * 1024L * 1024L

        @Volatile var currentPointsJson: String = "[]"
        @Volatile var currentActiveId: String? = null
        @Volatile var currentDoorCodesJson: String = "[]"
    }

    private var windowManager: WindowManager? = null

    private var headerWindowView: LinearLayout? = null
    private var headerWindowLp: WindowManager.LayoutParams? = null
    private var headerArrow: TextView? = null

    private var contentWindowView: LinearLayout? = null
    private var contentWindowLp: WindowManager.LayoutParams? = null
    private var contentContainer: LinearLayout? = null
    private var contentScroll: ScrollView? = null

    private var activeLabel: TextView? = null
    private var dotView: TextView? = null

    private var expanded = false
    private var pointsExpanded = true
    private var doorsExpanded = false
    private var isRunning = false
    private var lastClickAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    private var yandexMap: YandexMapView? = null
    private var osmMap: OsmMapView? = null
    private var fullMapWrapper: View? = null
    private var fullMapLayoutParams: WindowManager.LayoutParams? = null
    private var resetNorthButton: TextView? = null
    private var orientationTicker: Runnable? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            initOsmdroid()
            acquireWakeLock()

            currentPointsJson = OverlayPrefs.loadPoints(this)
            currentActiveId = OverlayPrefs.loadActiveId(this)
            currentDoorCodesJson = OverlayPrefs.loadDoorCodes(this)
            isRunning = OverlayPrefs.loadRunning(this)

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            OverlayBridge.setOverlayRunning(true)
            OverlayBridge.onOverlayStateChanged {
                handler.post { applyVisibility() }
            }

            buildHeaderWindow()
            applyVisibility()
        } catch (e: Throwable) {
            CrashLogger.log(this, "OverlayService_onCreate", e)
            stopSelf()
        }
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
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
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
                ACTION_UPDATE_DOOR_CODES -> {
                    intent.getStringExtra(EXTRA_DOOR_CODES_JSON)?.let {
                        currentDoorCodesJson = it
                        OverlayPrefs.saveDoorCodes(this, it)
                    }
                    handler.post { rebuildContent() }
                }
                ACTION_ORDER_TARGET -> {
                    val lat = intent.getDoubleExtra(EXTRA_ORDER_LAT, Double.NaN)
                    val lon = intent.getDoubleExtra(EXTRA_ORDER_LON, Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        OrderTargetStore.save(this, lat, lon)
                        handler.post { openFullMap(lat, lon) }
                    }
                }
                ACTION_ORDER_TARGET_CLEAR -> {
                    OrderTargetStore.clear(this)
                    handler.post { rebuildContent() }
                }
                ACTION_SET_HEADER_WIDTH -> {
                    val pct = intent.getIntExtra(
                        EXTRA_HEADER_WIDTH_PCT,
                        OverlayPrefs.loadHeaderWidthPct(this)
                    ).coerceIn(20, 100)
                    OverlayPrefs.saveHeaderWidthPct(this, pct)
                    headerWindowLp?.width = headerWidthFor(pct)
                    try {
                        windowManager?.updateViewLayout(headerWindowView, headerWindowLp)
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            CrashLogger.log(this, "OverlayService_onStartCommand", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            OverlayBridge.onOverlayStateChanged {}
            closeFullMap()
            removeContentWindow()
            headerWindowView?.let { windowManager?.removeView(it) }
            headerWindowView = null
            OverlayBridge.setOverlayRunning(false)
            releaseWakeLock()
        } catch (e: Throwable) {
            CrashLogger.log(this, "OverlayService_onDestroy", e)
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fmagnit::overlay_wakelock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "fmagnit overlay", NotificationManager.IMPORTANCE_MIN)
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

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        if (strokeColor != null) d.setStroke(dp(strokeWidthDp), strokeColor)
        return d
    }

    private fun lighten(color: Int, f: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun pressableBg(color: Int, radiusDp: Int): StateListDrawable {
        val d = StateListDrawable()
        d.addState(intArrayOf(-android.R.attr.state_enabled), roundedBg(lighten(color, -0.45f), radiusDp))
        d.addState(intArrayOf(android.R.attr.state_pressed), roundedBg(lighten(color, 0.14f), radiusDp))
        d.addState(intArrayOf(), roundedBg(color, radiusDp))
        return d
    }

    private fun guarded(onClick: () -> Unit): () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt >= CLICK_GUARD_MS) {
            lastClickAt = now
            onClick()
        }
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ===================== КООРДИНАТЫ =====================

    private fun getCurrentLocation(): Location? {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null }
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            }
            return best
        } catch (_: Throwable) { return null }
    }

    // ===================== ШАПКА =====================

    private fun headerWidthFor(pct: Int): Int =
        ((resources.displayMetrics.widthPixels * (pct / 100f)) - dp(16)).toInt().coerceAtLeast(dp(120))

    private fun buildHeaderWindow() {
        val ctx = this

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            background = pressableBg(COLOR_PILL, 24)
            setOnClickListener(guarded { toggleExpanded() })
            contentDescription = "Панель fmagnit, открыть или закрыть"
        }

        dotView = TextView(ctx).apply {
            text = "○"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, dp(8), 0)
        }

        activeLabel = TextView(ctx).apply {
            text = currentActiveNameOrPlaceholder()
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerArrow = TextView(ctx).apply {
            text = "▾"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, 0, 0)
        }

        header.addView(dotView)
        header.addView(activeLabel)
        header.addView(headerArrow)

        headerWindowView = header

        headerWindowLp = WindowManager.LayoutParams(
            headerWidthFor(OverlayPrefs.loadHeaderWidthPct(this)),
            dp(HEADER_HEIGHT_DP), windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(8)
        }

        try {
            windowManager?.addView(header, headerWindowLp)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка оверлея: ${e.message}", Toast.LENGTH_LONG).show()
        }

        applyDotColor()
    }

    // ===================== ПАНЕЛЬ =====================

    private fun showContentWindow() {
        if (contentWindowView != null) {
            rebuildContent()
            return
        }

        try {
            val ctx = this
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.92).toInt()

            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(COLOR_BG, 20, COLOR_BORDER, 1)
                setPadding(dp(14), dp(12), dp(14), dp(14))
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(ctx).apply {
                text = "fmagnit"
                setTextColor(COLOR_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(ctx).apply {
                text = "×"
                setTextColor(COLOR_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                background = pressableBg(COLOR_INACTIVE, 12)
                minWidth = dp(TOUCH_DP); minHeight = dp(TOUCH_DP)
                contentDescription = "Закрыть панель"
                setOnClickListener(guarded { toggleExpanded() })
            })
            wrapper.addView(topRow)

            wrapper.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also {
                    it.topMargin = dp(10); it.bottomMargin = dp(10)
                }
                setBackgroundColor(COLOR_BORDER)
            })

            contentContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            val maxContentH = (dm.heightPixels * 0.78).toInt()
            val scroll = object : ScrollView(ctx) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val capped = MeasureSpec.makeMeasureSpec(maxContentH, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, capped)
                }
            }
            scroll.addView(
                contentContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            contentScroll = scroll
            wrapper.addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            contentWindowView = wrapper

            contentWindowLp = WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager?.addView(wrapper, contentWindowLp)
            rebuildContent()
        } catch (e: Throwable) {
            CrashLogger.log(this, "showContentWindow", e)
            contentWindowView = null
            contentWindowLp = null
            contentContainer = null
            contentScroll = null
        }
    }

    private fun removeContentWindow() {
        try {
            contentWindowView?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        contentWindowView = null
        contentWindowLp = null
        contentContainer = null
        contentScroll = null
        doorsExpanded = false
    }

    private fun toggleExpanded() {
        expanded = !expanded
        headerArrow?.text = if (expanded) "▴" else "▾"
        if (expanded) {
            reloadDoorCodesFromFile()
            showContentWindow()
        } else {
            removeContentWindow()
        }
    }

    private fun currentActiveNameOrPlaceholder(): String {
        val id = currentActiveId ?: return "не выбрано"
        return parsePoints(currentPointsJson).firstOrNull { it.first == id }?.second ?: "не выбрано"
    }

    private fun applyDotColor() {
        dotView?.text = if (isRunning) "●" else "○"
        dotView?.setTextColor(if (isRunning) COLOR_SUCCESS else COLOR_ACCENT)
        activeLabel?.contentDescription = currentActiveNameOrPlaceholder()
    }

    // ===================== UI-ХЕЛПЕРЫ =====================

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(COLOR_SURFACE, 14)
        setPadding(dp(12), dp(10), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(10) }
    }

    private fun sectionTitle(text: String, expanded: Boolean?, onClick: (() -> Unit)?): TextView {
        return TextView(this).apply {
            this.text = if (expanded == null) text else text + (if (expanded) "  ▴" else "  ▾")
            setTextColor(if (onClick != null) COLOR_FG else COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
            if (onClick != null) {
                minHeight = dp(TOUCH_DP)
                gravity = Gravity.CENTER_VERTICAL
                background = pressableBg(COLOR_SURFACE, 8)
                setOnClickListener(guarded(onClick))
            }
        }
    }

    private fun actionBtn(text: String, bg: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(TOUCH_DP)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = pressableBg(bg, 12)
            setOnClickListener(guarded(onClick))
        }
    }

    private fun row2(a: View, b: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(GAP_DP) }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = dp(GAP_DP / 2) })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = dp(GAP_DP / 2) })
        }
    }

    private fun mutedLine(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(2), 0, dp(2))
    }

    // ===================== КАРТОЧКИ =====================

    private fun buildOrderCard(lat: Double, lon: Double): LinearLayout {
        val c = card()
        c.addView(sectionTitle("ЗАКАЗ КЛИЕНТА", null, null))
        c.addView(TextView(this).apply {
            text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(4))
        })

        val nearby = findNearbyDoorCodes(lat, lon)
        if (nearby.isNotEmpty()) {
            c.addView(TextView(this).apply {
                text = "Код: " + nearby.joinToString(", ") { it.code }
                setTextColor(COLOR_SUCCESS)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
        } else if (hasNearbyPointWithoutCode(lat, lon)) {
            c.addView(mutedLine("Код домофона: пока нет"))
        }

        val go = actionBtn("ПЕРЕЙТИ", COLOR_ACCENT) {
            try {
                isRunning = MockManager.start(this@OverlayService, lat, lon, 5.0, 0.0)
                OverlayPrefs.saveRunning(this@OverlayService, isRunning)
                rebuildContent()
            } catch (_: Throwable) {}
        }
        val map = actionBtn("КАРТА", COLOR_INACTIVE) { openFullMap(lat, lon) }
        c.addView(row2(go, map))

        c.addView(TextView(this).apply {
            text = "Закрыть заказ"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            minHeight = dp(TOUCH_DP)
            background = pressableBg(COLOR_SURFACE, 8)
            setOnClickListener(guarded {
                OrderTargetStore.clear(this@OverlayService)
                rebuildContent()
            })
        })
        return c
    }

    private fun buildPointsCard(): LinearLayout {
        val c = card()
        val points = parsePoints(currentPointsJson)
        c.addView(sectionTitle("ТОЧКИ (${points.size})", pointsExpanded) {
            pointsExpanded = !pointsExpanded
            rebuildContent()
        })
        if (!pointsExpanded) return c

        if (points.isEmpty()) {
            c.addView(mutedLine("Нет точек. Добавь в приложении fmagnit."))
            return c
        }
        points.forEach { (id, name) ->
            val isActive = id == currentActiveId
            c.addView(TextView(this).apply {
                text = (if (isActive) "●  " else "○  ") + name
                setTextColor(if (isActive) COLOR_ACCENT else COLOR_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minHeight = dp(TOUCH_DP)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = pressableBg(COLOR_SURFACE, 8)
                contentDescription = if (isActive) "$name, активная точка" else name
                setOnClickListener(guarded {
                    currentActiveId = id
                    OverlayPrefs.saveActiveId(this@OverlayService, id)
                    activeLabel?.text = name
                    rebuildContent()
                })
            })
        }
        return c
    }

    private fun buildActionsCard(): LinearLayout {
        val c = card()
        c.addView(sectionTitle("ДЕЙСТВИЯ", null, null))

        val canStart = isRunning || currentActiveId != null
        val toggle = actionBtn(
            if (isRunning) "СТОП" else "СТАРТ",
            if (isRunning) COLOR_INACTIVE else COLOR_ACCENT
        ) {
            try {
                if (isRunning) {
                    MockManager.stop(this@OverlayService)
                    isRunning = false
                    OverlayPrefs.saveRunning(this@OverlayService, false)
                } else {
                    val id = currentActiveId
                    if (id != null) {
                        val point = parsePointsFull(currentPointsJson).firstOrNull { it.first == id }
                        if (point != null) {
                            isRunning = MockManager.start(this@OverlayService, point.second, point.third, point.fourth, 0.0)
                            OverlayPrefs.saveRunning(this@OverlayService, isRunning)
                        }
                    }
                }
                rebuildContent()
            } catch (_: Throwable) {}
        }
        toggle.isEnabled = canStart
        toggle.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        c.addView(toggle)
        if (!canStart) {
            c.addView(mutedLine("Выбери точку в списке выше — тогда СТАРТ заработает."))
        }

        c.addView(row2(
            actionBtn("ВОССТАНОВИТЬ GPS", COLOR_INACTIVE) {
                try {
                    MockManager.forceReset(this@OverlayService)
                    isRunning = false
                    OverlayPrefs.saveRunning(this@OverlayService, false)
                    Toast.makeText(this@OverlayService, "GPS восстановлен.", Toast.LENGTH_LONG).show()
                    rebuildContent()
                } catch (_: Throwable) {}
            },
            actionBtn("СОХРАНИТЬ КОД GPS", COLOR_INACTIVE) { openAddDoorCodeDialog() }
        ))
        return c
    }

    private fun buildDoorsCard(): LinearLayout {
        val c = card()
        val doors = parseDoorPoints(currentDoorCodesJson)
        c.addView(sectionTitle("ДОМОФОНЫ (${doors.size})", doorsExpanded) {
            doorsExpanded = !doorsExpanded
            rebuildContent()
        })
        if (!doorsExpanded) return c

        if (doors.isEmpty()) {
            c.addView(mutedLine("Пока нет кодов. Добавь в приложении или кнопкой выше."))
            return c
        }

        val myLoc = try { getCurrentLocation() } catch (_: Throwable) { null }
        val sortedDoors = if (myLoc != null) {
            doors.map {
                it.copy(distance = distanceMeters(myLoc.latitude, myLoc.longitude, it.lat, it.lon))
            }.sortedBy { it.distance }
        } else {
            doors.sortedBy { it.address.lowercase(Locale.US) }
        }

        sortedDoors.forEach { d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            row.addView(TextView(this).apply {
                text = d.code
                setTextColor(COLOR_SUCCESS)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
            })
            val sub = buildString {
                if (d.address.isNotEmpty()) append(d.address)
                if (myLoc != null) {
                    if (isNotEmpty()) append("  •  ")
                    append("${d.distance.toInt()} м")
                }
            }
            if (sub.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = sub
                    setTextColor(COLOR_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            c.addView(row)
        }
        return c
    }

    // ===================== ИКОНКИ МЕТОК =====================

    private fun waypointIcon(): Bitmap? {
        val sizePx = dp(28)
        val square = TextView(this).apply {
            text = "М"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(COLOR_MARKER_BG, 6)
            width = sizePx; height = sizePx
        }
        return viewToBitmap(square, sizePx, sizePx)
    }

    private fun waypointPlate(name: String): Bitmap? {
        if (name.isEmpty()) return null
        val plate = TextView(this).apply {
            text = name
            setTextColor(COLOR_PLATE_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundedBg(COLOR_PLATE_BG, 4)
            maxLines = 1
        }
        return viewToBitmap(plate, dp(140), dp(22), exactWidth = false)
    }

    private fun myLocationIcon(): Bitmap? {
        val sizePx = dp(24)
        val view = TextView(this).apply {
            background = roundedBg(COLOR_MY_LOCATION, 12)
            width = sizePx; height = sizePx
        }
        return viewToBitmap(view, sizePx, sizePx)
    }

    private fun doorIcon(code: String): Bitmap? {
        val view = TextView(this).apply {
            text = "Ключ $code"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg(COLOR_SUCCESS, 8)
        }
        return viewToBitmap(view, dp(120), dp(30), exactWidth = false)
    }

    private fun viewToBitmap(view: View, widthPx: Int, heightPx: Int, exactWidth: Boolean = true): Bitmap? {
        return try {
            val wSpec = if (exactWidth) View.MeasureSpec.makeMeasureSpec(widthPx.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
                        else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
            view.measure(wSpec, hSpec)

            val w = if (exactWidth) widthPx.coerceAtLeast(1) else view.measuredWidth.coerceAtLeast(1)
            val h = heightPx.coerceAtLeast(1)
            view.layout(0, 0, w, h)

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            bmp
        } catch (_: Throwable) { null }
    }

    // ===================== ДОМОФОНЫ =====================

    private data class DoorPoint(
        val id: String,
        val code: String,
        val lat: Double,
        val lon: Double,
        val distance: Double = 0.0,
        val address: String = ""
    )

    private fun parseDoorPoints(json: String): List<DoorPoint> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<DoorPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                val code = obj.optString("code", "")
                if (code.isBlank()) continue
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(DoorPoint(id, code, lat, lon, address = obj.optString("address", "")))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun findNearbyDoorCodes(orderLat: Double, orderLon: Double): List<DoorPoint> {
        val points = parseDoorPoints(currentDoorCodesJson)
        if (points.isEmpty()) return emptyList()
        return points.map { p -> p.copy(distance = distanceMeters(orderLat, orderLon, p.lat, p.lon)) }
            .filter { it.distance <= DOOR_SEARCH_RADIUS_M }
            .sortedBy { it.distance }
    }

    private fun hasNearbyPointWithoutCode(orderLat: Double, orderLon: Double): Boolean {
        return try {
            val arr = JSONArray(currentDoorCodesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val d = distanceMeters(orderLat, orderLon, lat, lon)
                if (d <= DOOR_SEARCH_RADIUS_M) {
                    val code = obj.optString("code", "")
                    if (code.isBlank()) return true
                }
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun reloadDoorCodesFromFile() {
        try {
            currentDoorCodesJson = DoorCodeNativeStore.asJsonArrayString(this)
            OverlayPrefs.saveDoorCodes(this, currentDoorCodesJson)
        } catch (_: Throwable) {}
    }

    // ===================== GPS-ДИАЛОГ =====================

    private fun openAddDoorCodeDialog() {
        try {
            val last: Location? = getCurrentLocation()
            if (last == null) {
                Toast.makeText(this, "Нет координат. Включи GPS или запусти mock.", Toast.LENGTH_LONG).show()
                return
            }

            val lat = last.latitude
            val lon = last.longitude

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }

            val addrEt = EditText(this).apply {
                hint = "Адрес или название"
                setSingleLine(true)
                contentDescription = "Адрес или название точки домофона"
            }
            val codeEt = EditText(this).apply {
                hint = "Код домофона (например, 1234#)"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                contentDescription = "Код домофона"
            }
            val coordsTv = TextView(this).apply {
                text = String.format(Locale.US, "Координаты: %.6f, %.6f", lat, lon)
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, 0)
            }

            wrapper.addView(addrEt)
            wrapper.addView(codeEt)
            wrapper.addView(coordsTv)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Новый код домофона")
                .setView(wrapper)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
                .create()

            dialog.window?.setType(windowType())
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val addr = addrEt.text.toString().trim()
                    val code = codeEt.text.toString().trim()
                    if (code.isEmpty()) {
                        codeEt.error = "Введи код"
                        return@setOnClickListener
                    }
                    val newId = DoorCodeNativeStore.add(this, code, addr, lat, lon)
                    if (newId != null) {
                        reloadDoorCodesFromFile()
                        dialog.dismiss()
                        rebuildContent()
                        Toast.makeText(this, "Код сохранён", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Не удалось сохранить", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.show()
        } catch (e: Throwable) {
            CrashLogger.log(this, "openAddDoorCodeDialog", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== ПОЛНАЯ КАРТА =====================

    private fun openFullMap(lat: Double, lon: Double) {
        try {
            closeFullMap()
            expanded = false
            headerArrow?.text = "▾"
            removeContentWindow()

            val ym = MapKitHub.createMapView(this, true)
            if (ym != null) {
                buildYandexMapWindow(ym, lat, lon)
            } else {
                buildOsmMapWindow(lat, lon)
            }
        } catch (e: Throwable) {
            CrashLogger.log(this, "openFullMap", e)
            closeFullMap()
        }
    }

    private fun buildYandexMapWindow(ym: YandexMapView, lat: Double, lon: Double) {
        val ctx = this
        yandexMap = ym

        try {
            MapKitHub.placemark(ym.map, lat, lon)
            val pts = parsePointsFullWithName(currentPointsJson)
            val wIcon = waypointIcon()
            pts.forEach { (_, name, pLat, pLon, _) ->
                MapKitHub.placemark(ym.map, pLat, pLon, wIcon)
                val plate = waypointPlate(name)
                if (plate != null) MapKitHub.placemark(ym.map, pLat, pLon, plate)
            }
            try {
                val myLoc = getCurrentLocation()
                if (myLoc != null) MapKitHub.placemark(ym.map, myLoc.latitude, myLoc.longitude, myLocationIcon())
            } catch (_: Throwable) {}
            val nearby = findNearbyDoorCodes(lat, lon)
            if (nearby.isNotEmpty()) {
                val best = nearby.first()
                MapKitHub.placemark(ym.map, best.lat, best.lon, doorIcon(best.code))
            }
            MapKitHub.camera(ym.map, lat, lon, 17f)
        } catch (e: Throwable) {
            CrashLogger.log(this, "yandex_map_objects", e)
        }

        val container = FrameLayout(ctx)
        container.addView(ym, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addMapControls(container, isYandex = true)

        val wrapper = mapWrapper(container)
        addMapWindow(wrapper)

        MapKitHub.start()
        try { ym.onStart() } catch (_: Throwable) {}
        startOrientationTicker()
    }

    private fun buildOsmMapWindow(lat: Double, lon: Double) {
        val ctx = this
        val map = OsmMapView(ctx)
        osmMap = map
        try {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setBuiltInZoomControls(false)
            map.setUseDataConnection(true)
            map.setMultiTouchControls(true)
            map.controller.setZoom(17.0)

            val order = Marker(map)
            order.position = GeoPoint(lat, lon)
            order.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            order.title = "Заказ"
            map.overlays.add(order)

            val pts = parsePointsFullWithName(currentPointsJson)
            val wIcon = waypointIcon()
            pts.forEach { (_, name, pLat, pLon, _) ->
                val m = Marker(map)
                m.position = GeoPoint(pLat, pLon)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                m.setOnMarkerClickListener { _, _ -> true }
                if (wIcon != null) m.icon = BitmapDrawable(resources, wIcon)
                map.overlays.add(m)
                val plate = waypointPlate(name)
                if (plate != null) {
                    val lm = Marker(map)
                    lm.position = GeoPoint(pLat, pLon)
                    lm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
                    lm.setOnMarkerClickListener { _, _ -> true }
                    lm.icon = BitmapDrawable(resources, plate)
                    map.overlays.add(lm)
                }
            }

            try {
                val myLoc = getCurrentLocation()
                if (myLoc != null) {
                    val mm = Marker(map)
                    mm.position = GeoPoint(myLoc.latitude, myLoc.longitude)
                    mm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    mm.setOnMarkerClickListener { _, _ -> true }
                    val ic = myLocationIcon()
                    if (ic != null) mm.icon = BitmapDrawable(resources, ic)
                    map.overlays.add(mm)
                }
            } catch (_: Throwable) {}

            val nearby = findNearbyDoorCodes(lat, lon)
            if (nearby.isNotEmpty()) {
                val best = nearby.first()
                val dm = Marker(map)
                dm.position = GeoPoint(best.lat, best.lon)
                dm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                dm.setOnMarkerClickListener { _, _ -> true }
                val ic = doorIcon(best.code)
                if (ic != null) dm.icon = BitmapDrawable(resources, ic)
                map.overlays.add(dm)
            }

            try {
                val rotation = RotationGestureOverlay(map)
                rotation.isEnabled = true
                map.overlays.add(rotation)
            } catch (_: Throwable) {}

            map.controller.setCenter(GeoPoint(lat, lon))
            map.invalidate()
        } catch (e: Throwable) {
            CrashLogger.log(this, "osm_map_objects", e)
        }

        val container = FrameLayout(ctx)
        container.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addMapControls(container, isYandex = false)

        val attr = TextView(ctx).apply {
            text = "© OpenStreetMap contributors"
            setTextColor(0xFF444444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = roundedBg(0xB3FFFFFF.toInt(), 6)
        }
        container.addView(attr, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(10); bottomMargin = dp(10)
        })

        val wrapper = mapWrapper(container)
        addMapWindow(wrapper)
        startOrientationTicker()
    }

    /** Контролы по краям, НИЗ свободен под лого Яндекса / attribution OSM. */
    private fun addMapControls(container: FrameLayout, isYandex: Boolean) {
        val ctx = this

        val closeBtn = TextView(ctx).apply {
            text = "×"
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            background = pressableBg(COLOR_ACCENT, 24)
            contentDescription = "Закрыть карту"
            setOnClickListener(guarded { closeFullMap() })
        }
        container.addView(closeBtn, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12); rightMargin = dp(12)
        })

        val north = TextView(ctx).apply {
            text = "N"
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pressableBg(COLOR_PILL, 24)
            contentDescription = "Сбросить ориентацию карты на север"
            visibility = View.GONE
            setOnClickListener {
                yandexMap?.let { MapKitHub.resetNorth(it.map) }
                osmMap?.let {
                    it.mapOrientation = 0f
                    it.invalidate()
                }
                visibility = View.GONE
            }
        }
        resetNorthButton = north
        container.addView(north, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(12); leftMargin = dp(12)
        })

        val zoomCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val zoomOut = TextView(ctx).apply {
            text = "−"
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pressableBg(COLOR_PILL, 12)
            contentDescription = "Уменьшить масштаб"
            setOnClickListener {
                yandexMap?.let { MapKitHub.zoomBy(it.map, -1f) }
                osmMap?.controller?.zoomOut()
            }
        }
        val zoomIn = TextView(ctx).apply {
            text = "+"
            setTextColor(COLOR_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pressableBg(COLOR_PILL, 12)
            contentDescription = "Увеличить масштаб"
            setOnClickListener {
                yandexMap?.let { MapKitHub.zoomBy(it.map, +1f) }
                osmMap?.controller?.zoomIn()
            }
        }
        zoomCol.addView(zoomOut, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)).also { it.bottomMargin = dp(GAP_DP) })
        zoomCol.addView(zoomIn, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        container.addView(zoomCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(12)
        })
    }

    private fun mapWrapper(container: FrameLayout): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_BG, 16, COLOR_BORDER, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun addMapWindow(wrapper: LinearLayout) {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.92).toInt()
        val h = (dm.heightPixels * 0.85).toInt()

        fullMapLayoutParams = WindowManager.LayoutParams(
            w, h, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager?.addView(wrapper, fullMapLayoutParams)
        fullMapWrapper = wrapper
    }

    private fun startOrientationTicker() {
        val ticker = object : Runnable {
            override fun run() {
                try {
                    if (yandexMap == null && osmMap == null) return
                    val az = yandexMap?.let { MapKitHub.azimuth(it.map) }
                        ?: (osmMap?.mapOrientation ?: 0f)
                    resetNorthButton?.visibility = if (Math.abs(az) > 1f) View.VISIBLE else View.GONE
                } catch (_: Throwable) {}
                handler.postDelayed(this, 300L)
            }
        }
        orientationTicker = ticker
        handler.post(ticker)
    }

    private fun closeFullMap() {
        orientationTicker?.let { handler.removeCallbacks(it) }
        orientationTicker = null
        try { yandexMap?.onStop() } catch (_: Throwable) {}
        MapKitHub.stop()
        yandexMap = null
        try { osmMap?.onDetach() } catch (_: Throwable) {}
        osmMap = null
        try {
            fullMapWrapper?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        fullMapWrapper = null
        fullMapLayoutParams = null
        resetNorthButton = null
    }

    // ===================== КОНТЕНТ =====================

    private fun rebuildContent() {
        try {
            isRunning = try { MockManager.isRunning() } catch (_: Throwable) { false }
            applyDotColor()
            activeLabel?.text = currentActiveNameOrPlaceholder()

            val container = contentContainer ?: return
            val scrollY = contentScroll?.scrollY ?: 0
            container.removeAllViews()

            val order = OrderTargetStore.load(this)
            if (order != null) {
                container.addView(buildOrderCard(order.first, order.second))
            }
            container.addView(buildPointsCard())
            container.addView(buildActionsCard())
            container.addView(buildDoorsCard())

            contentScroll?.post { contentScroll?.scrollTo(0, scrollY) }
        } catch (e: Throwable) {
            CrashLogger.log(this, "rebuildContent", e)
        }
    }

    // ===================== ПАРСЕРЫ =====================

    private fun parsePointsFull(json: String): List<Quadruple> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Quadruple>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                out.add(Quadruple(id, obj.optDouble("latitude", 0.0), obj.optDouble("longitude", 0.0), obj.optDouble("accuracy", 3.0)))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    private fun parsePointsFullWithName(json: String): List<WaypointTuple> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<WaypointTuple>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(WaypointTuple(id, obj.optString("name", ""), lat, lon, obj.optDouble("accuracy", 3.0)))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    data class Quadruple(val first: String, val second: Double, val third: Double, val fourth: Double)
    data class WaypointTuple(val id: String, val name: String, val lat: Double, val lon: Double, val accuracy: Double)

    private fun parsePoints(json: String): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Pair<String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isNotEmpty()) out.add(id to obj.optString("name", "точка"))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    private fun applyVisibility() {
        try {
            val h = headerWindowView ?: return
            val usageOk = ForegroundDetector.hasUsageAccess(this)
            val vis = if (!usageOk) View.VISIBLE else if (OverlayBridge.isTargetAppForeground) View.VISIBLE else View.GONE
            h.visibility = vis
            contentWindowView?.visibility = vis
        } catch (_: Throwable) {}
    }
}