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
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"

        private const val COLOR_HEADER_BG = 0xFF1E1E1E.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DOT_ACTIVE = 0xFF009E27.toInt()
        private const val COLOR_DOT_IDLE = 0xFFE30613.toInt()
        private const val COLOR_MUTED = 0xFF9E9E9E.toInt()
        private const val COLOR_ACCENT = 0xFFE30613.toInt()
        private const val COLOR_INACTIVE = 0xFF2A2A2A.toInt()
        private const val COLOR_DOOR_OK = 0xFF009E27.toInt()
        private const val COLOR_MARKER_BG = 0xFFE30613.toInt()
        private const val COLOR_PLATE_BG = 0xFFFFFFFF.toInt()
        private const val COLOR_PLATE_TEXT = 0xFF1E1E1E.toInt()
        
        private const val COLOR_MY_LOCATION = 0xFF2196F3.toInt()

        private const val HEADER_HEIGHT_DP = 56
        private const val STATUS_FONT_SP = 12f
        private const val DOT_SIZE_SP = 14f

        private const val DOOR_SEARCH_RADIUS_M = 50.0

        const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"
        const val ACTION_UPDATE_DOOR_CODES =
            "com.s1xck.fmagnit.OVERLAY_UPDATE_DOOR_CODES"
        const val ACTION_ORDER_TARGET = "com.s1xck.fmagnit.OVERLAY_ORDER_TARGET"
        const val ACTION_ORDER_TARGET_CLEAR =
            "com.s1xck.fmagnit.OVERLAY_ORDER_CLEAR"
        const val ACTION_ORDER_START_MOCK =
            "com.s1xck.fmagnit.ORDER_START_MOCK"

        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"
        const val EXTRA_DOOR_CODES_JSON = "door_codes_json"
        const val EXTRA_ORDER_LAT = "order_lat"
        const val EXTRA_ORDER_LON = "order_lon"

        const val ACTION_DOOR_CODES_CHANGED =
            "com.s1xck.fmagnit.DOOR_CODES_CHANGED"

        private const val TILE_CACHE_BYTES = 100L * 1024L * 1024L

        @Volatile var currentPointsJson: String = "[]"
        @Volatile var currentActiveId: String? = null
        @Volatile var currentDoorCodesJson: String = "[]"
    }

    private var windowManager: WindowManager? = null

    private var headerWindowView: LinearLayout? = null
    private var headerWindowLp: WindowManager.LayoutParams? = null

    private var contentWindowView: ScrollView? = null
    private var contentWindowLp: WindowManager.LayoutParams? = null
    private var contentContainer: LinearLayout? = null

    private var activeLabel: TextView? = null
    private var dotView: TextView? = null

    private var expanded = false
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())

    private var fullMap: MapView? = null
    private var fullMapWrapper: View? = null
    private var fullMapLayoutParams: WindowManager.LayoutParams? = null
    private var resetNorthButton: TextView? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            checkLastStepForCrash()

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

    // ===================== ОТСЛЕЖИВАНИЕ КРАША =====================

    private fun step(s: String) {
        try {
            val prefs = getSharedPreferences("fm_crash_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_step", s).putLong("step_time", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }

    private fun checkLastStepForCrash() {
        try {
            val prefs = getSharedPreferences("fm_crash_prefs", Context.MODE_PRIVATE)
            val stepText = prefs.getString("last_step", null)
            val stepTime = prefs.getLong("step_time", 0L)
            // Если упали меньше минуты назад
            if (stepText != null && (System.currentTimeMillis() - stepTime < 60000)) {
                CrashLogger.log(
                    this,
                    "CRASH_WAS_AT_STEP",
                    Throwable("Ошибка произошла на шаге: $stepText")
                )
            }
            prefs.edit().remove("last_step").apply()
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
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
                        handler.post { 
                            rebuildContent()
                            // Авто-открытие карты отключено для стабильности
                        }
                    }
                }
                ACTION_ORDER_TARGET_CLEAR -> {
                    OrderTargetStore.clear(this)
                    handler.post { rebuildContent() }
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
            if (headerWindowView?.parent != null) {
                windowManager?.removeView(headerWindowView)
            }
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
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "fmagnit::overlay_wakelock"
            )
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
        val openIntent = Intent(
            this,
            com.google.android.apps.maps.MapsActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        return d
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ===================== КООРДИНАТЫ =====================

    private fun getCurrentLocation(): Location? {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                val loc = try {
                    lm.getLastKnownLocation(p)
                } catch (_: SecurityException) {
                    null
                } catch (_: Throwable) {
                    null
                }
                if (loc != null) {
                    if (best == null || loc.time > best.time) {
                        best = loc
                    }
                }
            }
            return best
        } catch (_: Throwable) {
            return null
        }
    }

    // ===================== ОКНО ШАПКИ =====================

    private fun buildHeaderWindow() {
        try {
            step("buildHeader_start")
            val ctx = this
            val screenW = resources.displayMetrics.widthPixels

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(16), 0)
                background = roundedBg(COLOR_HEADER_BG, 0)
                setOnClickListener { toggleExpanded() }
            }

            dotView = TextView(ctx).apply {
                text = "●"
                setTextColor(COLOR_DOT_IDLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DOT_SIZE_SP)
                setPadding(0, 0, dp(12), 0)
            }

            activeLabel = TextView(ctx).apply {
                text = currentActiveNameOrPlaceholder()
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_FONT_SP)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            header.addView(dotView)
            header.addView(activeLabel)

            headerWindowView = header

            headerWindowLp = WindowManager.LayoutParams(
                screenW / 2,
                dp(HEADER_HEIGHT_DP),
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            step("buildHeader_before_add")
            if (headerWindowView?.parent == null) {
                windowManager?.addView(header, headerWindowLp)
            }
            step("buildHeader_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "buildHeaderWindow", e)
            Toast.makeText(this, "Ошибка оверлея: ${e.message}", Toast.LENGTH_LONG).show()
        }

        applyDotColor()
    }

    // ===================== ОКНО КОНТЕНТА =====================

    private fun showContentWindow() {
        if (contentWindowView != null && contentWindowView?.parent != null) {
            rebuildContent()
            return
        }

        try {
            step("showContent_start")
            val ctx = this
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.9).toInt()
            // Ограничиваем высоту окна 85% экрана, чтобы избежать BadTokenException
            val h = (dm.heightPixels * 0.85).toInt()

            // Используем ScrollView как корень, чтобы контент не вылезал за пределы
            val scrollWrapper = ScrollView(ctx).apply {
                background = roundedBg(COLOR_HEADER_BG, 16)
                isFillViewport = true
            }

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(ctx).apply {
                text = "fmagnit"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            topRow.addView(TextView(ctx).apply {
                text = "×"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
                background = roundedBg(COLOR_ACCENT, 20)
                setPadding(dp(12), dp(0), dp(12), dp(4))
                setOnClickListener { toggleExpanded() }
            })
            inner.addView(topRow)

            inner.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).also {
                    it.topMargin = dp(8)
                    it.bottomMargin = dp(8)
                }
                setBackgroundColor(0x22FFFFFF)
            })

            contentContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            inner.addView(contentContainer)
            scrollWrapper.addView(inner)

            contentWindowView = scrollWrapper

            contentWindowLp = WindowManager.LayoutParams(
                w,
                h,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            step("showContent_before_add")
            if (contentWindowView?.parent == null) {
                windowManager?.addView(scrollWrapper, contentWindowLp)
            }
            step("showContent_after_add")
            rebuildContent()
            step("showContent_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "showContentWindow", e)
            contentWindowView = null
            contentWindowLp = null
            contentContainer = null
            Toast.makeText(this, "Ошибка окна: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeContentWindow() {
        try {
            if (contentWindowView?.parent != null) {
                windowManager?.removeView(contentWindowView)
            }
        } catch (_: Throwable) {}
        contentWindowView = null
        contentWindowLp = null
        contentContainer = null
    }

    private fun toggleExpanded() {
        expanded = !expanded
        if (expanded) {
            showContentWindow()
        } else {
            removeContentWindow()
        }
    }

    private fun currentActiveNameOrPlaceholder(): String {
        val id = currentActiveId ?: return "не выбрано"
        return parsePoints(currentPointsJson)
            .firstOrNull { it.first == id }?.second
            ?: "не выбрано"
    }

    private fun applyDotColor() {
        dotView?.setTextColor(
            if (isRunning) COLOR_DOT_ACTIVE else COLOR_DOT_IDLE
        )
    }

    // ===================== КАРТА =====================

    private fun createMapView(): MapView {
        // Обязательно используем ContextThemeWrapper для MapView!
        val themedCtx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val map = MapView(themedCtx)
        try {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setUseDataConnection(true)
            map.setMultiTouchControls(true)
            map.controller.setZoom(17.0)
            map.invalidate()
        } catch (_: Throwable) {}
        return map
    }

    private fun addOrderMarker(map: MapView, lat: Double, lon: Double) {
        try {
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Заказ"
            map.overlays.add(marker)
        } catch (_: Throwable) {}
    }

    private fun addWaypointMarker(
        map: MapView,
        lat: Double,
        lon: Double,
        name: String
    ) {
        try {
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = null
            marker.setOnMarkerClickListener { _, _ -> true }

            val ctx = this
            val sizePx = dp(28)
            val square = TextView(ctx).apply {
                text = "М"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedBg(COLOR_MARKER_BG, 6)
                width = sizePx
                height = sizePx
            }
            
            val bmp = viewToBitmap(square, sizePx, sizePx)
            if (bmp != null) {
                marker.icon = BitmapDrawable(resources, bmp)
                map.overlays.add(marker)
            }

            if (name.isNotEmpty()) {
                val label = Marker(map)
                label.position = GeoPoint(lat, lon)
                label.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
                label.title = null
                label.setOnMarkerClickListener { _, _ -> true }

                val plate = TextView(ctx).apply {
                    text = name
                    setTextColor(COLOR_PLATE_TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = roundedBg(COLOR_PLATE_BG, 4)
                    maxLines = 1
                }
                val wPx = dp(140)
                val hPx = dp(22)
                val bmpLabel = viewToBitmap(plate, wPx, hPx, exactWidth = false)
                if (bmpLabel != null) {
                    label.icon = BitmapDrawable(resources, bmpLabel)
                    map.overlays.add(label)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun addMyLocationMarker(map: MapView, lat: Double, lon: Double) {
        try {
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = null
            marker.setOnMarkerClickListener { _, _ -> true }

            val ctx = this
            val sizePx = dp(24)
            val view = TextView(ctx).apply {
                background = roundedBg(COLOR_MY_LOCATION, 12) // Синий круг
                width = sizePx
                height = sizePx
            }
            val bmp = viewToBitmap(view, sizePx, sizePx)
            if (bmp != null) {
                marker.icon = BitmapDrawable(resources, bmp)
                map.overlays.add(marker)
            }
        } catch (_: Throwable) {}
    }

    private fun addDoorCodeMarker(map: MapView, lat: Double, lon: Double, code: String) {
        try {
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = null
            marker.setOnMarkerClickListener { _, _ -> true }

            val ctx = this
            val view = TextView(ctx).apply {
                text = "Ключ $code"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBg(COLOR_DOOR_OK, 8) // Зеленый
            }
            val wPx = dp(120)
            val hPx = dp(30)
            val bmp = viewToBitmap(view, wPx, hPx, exactWidth = false)
            if (bmp != null) {
                marker.icon = BitmapDrawable(resources, bmp)
                map.overlays.add(marker)
            }
        } catch (_: Throwable) {}
    }

    private fun viewToBitmap(
        view: View,
        widthPx: Int,
        heightPx: Int,
        exactWidth: Boolean = true
    ): Bitmap? {
        return try {
            val wSpec = if (exactWidth)
                View.MeasureSpec.makeMeasureSpec(widthPx.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
            else
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
            view.measure(wSpec, hSpec)
            
            val w = if (exactWidth) widthPx.coerceAtLeast(1) else view.measuredWidth.coerceAtLeast(1)
            val h = heightPx.coerceAtLeast(1)
            view.layout(0, 0, w, h)
            
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            view.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    // ===================== ДОМОФОНЫ =====================

    private data class DoorPoint(
        val id: String,
        val code: String,
        val address: String,
        val lat: Double,
        val lon: Double,
        val distance: Double = 0.0
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
                val address = obj.optString("address", "")
                out.add(DoorPoint(id, code, address, lat, lon))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun findNearbyDoorCodes(
        orderLat: Double,
        orderLon: Double
    ): List<DoorPoint> {
        val points = parseDoorPoints(currentDoorCodesJson)
        if (points.isEmpty()) return emptyList()

        val withDist = points.map { p ->
            p.copy(
                distance = distanceMeters(
                    orderLat, orderLon, p.lat, p.lon
                )
            )
        }.filter { it.distance <= DOOR_SEARCH_RADIUS_M }

        return withDist.sortedBy { it.distance }
    }

    private fun hasNearbyPointWithoutCode(
        orderLat: Double,
        orderLon: Double
    ): Boolean {
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
        } catch (_: Throwable) {
            false
        }
    }

    private fun reloadDoorCodesFromFile() {
        try {
            currentDoorCodesJson = DoorCodeNativeStore.asJsonArrayString(this)
            OverlayPrefs.saveDoorCodes(this, currentDoorCodesJson)
        } catch (_: Throwable) {}
    }

    // ===================== ДИАЛОГИ =====================

    private fun openAddDoorCodeDialog() {
        try {
            step("openDoorDialog_start")
            val last: Location? = getCurrentLocation()

            if (last == null) {
                Toast.makeText(this, "Нет координат. Включи GPS или запусти mock.", Toast.LENGTH_LONG).show()
                return
            }

            val lat = last.latitude
            val lon = last.longitude

            // Обязательно используем ContextThemeWrapper для диалогов!
            val dialogCtx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            val wrapper = LinearLayout(dialogCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }

            val addrEt = EditText(dialogCtx).apply {
                hint = "Адрес или название"
                setSingleLine(true)
            }
            val codeEt = EditText(dialogCtx).apply {
                hint = "Код домофона (например, 1234#)"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val coordsTv = TextView(dialogCtx).apply {
                text = String.format(Locale.US, "Координаты: %.6f, %.6f", lat, lon)
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, 0)
            }

            wrapper.addView(addrEt)
            wrapper.addView(codeEt)
            wrapper.addView(coordsTv)

            val dialog = AlertDialog.Builder(dialogCtx)
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
                        val bcast = Intent(ACTION_DOOR_CODES_CHANGED)
                        bcast.setPackage(packageName)
                        try { sendBroadcast(bcast) } catch (_: Throwable) {}
                        dialog.dismiss()
                        rebuildContent()
                        Toast.makeText(this, "Код сохранён", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Не удалось сохранить", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            step("openDoorDialog_show")
            dialog.show()
            step("openDoorDialog_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "openAddDoorCodeDialog", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAllDoorCodesDialog(orderLat: Double, orderLon: Double) {
        try {
            step("allDoorsDialog_start")
            val dialogCtx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            val wrapper = LinearLayout(dialogCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            val searchEt = EditText(dialogCtx).apply {
                hint = "Поиск по коду или адресу"
                setSingleLine(true)
            }
            wrapper.addView(searchEt)

            val listContainer = LinearLayout(dialogCtx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val scroll = ScrollView(dialogCtx).apply {
                addView(listContainer)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(400)
                )
            }
            wrapper.addView(scroll)

            val dialog = AlertDialog.Builder(dialogCtx)
                .setTitle("Коды домофонов")
                .setView(wrapper)
                .setPositiveButton("Закрыть", null)
                .create()

            dialog.window?.setType(windowType())

            fun updateList(query: String) {
                try {
                    listContainer.removeAllViews()
                    
                    var doors = parseDoorPoints(currentDoorCodesJson)
                    
                    if (orderLat != 0.0 || orderLon != 0.0) {
                        doors = doors.map { d ->
                            d.copy(distance = distanceMeters(orderLat, orderLon, d.lat, d.lon))
                        }.sortedBy { it.distance }
                    } else {
                        doors = doors.sortedBy { it.address }
                    }

                    val filtered = doors.filter {
                        query.isEmpty() || 
                        it.code.contains(query, ignoreCase = true) || 
                        it.address.contains(query, ignoreCase = true)
                    }

                    if (filtered.isEmpty()) {
                        listContainer.addView(TextView(dialogCtx).apply {
                            text = "Ничего не найдено"
                            setTextColor(COLOR_MUTED)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setPadding(0, dp(16), 0, dp(16))
                            gravity = Gravity.CENTER
                        })
                    } else {
                        filtered.forEach { d ->
                            val row = LinearLayout(dialogCtx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, dp(8), 0, dp(8))
                            }
                            
                            val titleRow = LinearLayout(dialogCtx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            
                            titleRow.addView(TextView(dialogCtx).apply {
                                text = d.code
                                setTextColor(COLOR_TEXT)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                setTypeface(typeface, Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            
                            if (d.distance > 0 && d.distance < 500000.0) {
                                titleRow.addView(TextView(dialogCtx).apply {
                                    text = String.format(Locale.US, "%.0f м", d.distance)
                                    setTextColor(COLOR_MUTED)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                })
                            }
                            
                            row.addView(titleRow)
                            
                            if (d.address.isNotEmpty()) {
                                row.addView(TextView(dialogCtx).apply {
                                    text = d.address
                                    setTextColor(COLOR_MUTED)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                    setPadding(0, dp(2), 0, 0)
                                })
                            }
                            
                            listContainer.addView(row)
                            listContainer.addView(View(dialogCtx).apply {
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                                setBackgroundColor(0x11FFFFFF)
                            })
                        }
                    }
                } catch (_: Throwable) {}
            }

            searchEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateList(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            updateList("")
            step("allDoorsDialog_show")
            dialog.show()
            step("allDoorsDialog_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "showAllDoorCodesDialog", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== ПОЛНАЯ КАРТА =====================

    private fun openFullMap(lat: Double, lon: Double) {
        try {
            step("openFullMap_enter")
            closeFullMap()
            step("openFullMap_closed")
            
            expanded = false
            removeContentWindow()
            step("openFullMap_window_removed")

            val ctx = this
            fullMap = createMapView()
            step("openFullMap_map_created")
            
            fullMap?.let { map ->
                addOrderMarker(map, lat, lon)
                step("openFullMap_order_marker")
                
                val pts = parsePointsFullWithName(currentPointsJson)
                pts.forEach { (_, name, pLat, pLon, _) ->
                    addWaypointMarker(map, pLat, pLon, name)
                }
                step("openFullMap_waypoints_${pts.size}")

                var myLat: Double? = null
                var myLon: Double? = null
                try {
                    val myLoc = getCurrentLocation()
                    if (myLoc != null) {
                        myLat = myLoc.latitude
                        myLon = myLoc.longitude
                        addMyLocationMarker(map, myLat, myLon)
                    }
                } catch (_: Throwable) {}
                step("openFullMap_my_location")

                val nearbyDoors = findNearbyDoorCodes(lat, lon)
                if (nearbyDoors.isNotEmpty()) {
                    val bestDoor = nearbyDoors.first()
                    addDoorCodeMarker(map, bestDoor.lat, bestDoor.lon, bestDoor.code)
                }
                step("openFullMap_door_marker")

                try {
                    val rotation = RotationGestureOverlay(map)
                    rotation.isEnabled = true
                    map.overlays.add(rotation)
                } catch (_: Throwable) {}
                step("openFullMap_rotation")

                var minLat = lat
                var maxLat = lat
                var minLon = lon
                var maxLon = lon

                if (myLat != null && myLon != null) {
                    minLat = minOf(minLat, myLat)
                    maxLat = maxOf(maxLat, myLat)
                    minLon = minOf(minLon, myLon)
                    maxLon = maxOf(maxLon, myLon)
                }
                if (nearbyDoors.isNotEmpty()) {
                    val d = nearbyDoors.first()
                    minLat = minOf(minLat, d.lat)
                    maxLat = maxOf(maxLat, d.lat)
                    minLon = minOf(minLon, d.lon)
                    maxLon = maxOf(maxLon, d.lon)
                }

                if (minLat != maxLat || minLon != maxLon) {
                    val bb = BoundingBox(maxLat + 0.001, maxLon + 0.001, minLat - 0.001, minLon - 0.001)
                    map.zoomToBoundingBox(bb, true, dp(50))
                } else {
                    map.controller.setCenter(GeoPoint(lat, lon))
                    map.controller.setZoom(17.0)
                }
                map.invalidate()
            }
            step("openFullMap_map_ready")

            val container = FrameLayout(ctx)
            fullMap?.let {
                container.addView(
                    it,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
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

            val north = TextView(ctx).apply {
                text = "N"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedBg(COLOR_HEADER_BG, 20)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                visibility = View.GONE
                setOnClickListener {
                    fullMap?.mapOrientation = 0f
                    fullMap?.invalidate()
                    visibility = View.GONE
                }
            }
            resetNorthButton = north

            val northLp = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12)
                leftMargin = dp(12)
            }
            container.addView(north, northLp)

            val orientationTicker = object : Runnable {
                override fun run() {
                    try {
                        val m = fullMap ?: return
                        val bearing = m.mapOrientation
                        val visible = Math.abs(bearing) > 1f
                        resetNorthButton?.visibility = if (visible) View.VISIBLE else View.GONE
                    } catch (_: Throwable) {}
                    handler.postDelayed(this, 300L)
                }
            }
            handler.post(orientationTicker)

            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(COLOR_HEADER_BG, 16)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(
                    container,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                )
            }

            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.9).toInt()
            val h = (dm.heightPixels * 0.85).toInt()

            fullMapLayoutParams = WindowManager.LayoutParams(
                w, h, windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
            }

            step("openFullMap_before_add")
            if (wrapper.parent == null) {
                windowManager?.addView(wrapper, fullMapLayoutParams)
            }
            fullMapWrapper = wrapper
            step("openFullMap_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "openFullMap", e)
            closeFullMap()
        }
    }

    private fun closeFullMap() {
        try {
            if (fullMapWrapper?.parent != null) {
                windowManager?.removeView(fullMapWrapper)
            }
        } catch (_: Throwable) {}
        fullMapWrapper = null
        fullMapLayoutParams = null
        resetNorthButton = null
        try { fullMap?.onDetach() } catch (_: Throwable) {}
        fullMap = null
    }

    // ===================== КОНТЕНТ =====================

    private fun rebuildContent() {
        try {
            step("rebuildContent_start")
            isRunning = try { MockManager.isRunning() } catch (_: Throwable) { false }
            applyDotColor()
            activeLabel?.text = currentActiveNameOrPlaceholder()

            val container = contentContainer ?: return
            container.removeAllViews()

            val order = OrderTargetStore.load(this)
            if (order != null) {
                addOrderBlock(container, order.first, order.second)
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).also {
                        it.topMargin = dp(4)
                        it.bottomMargin = dp(8)
                    }
                    setBackgroundColor(0x22FFFFFF)
                })
            }
            step("rebuildContent_order_done")

            val points = parsePoints(currentPointsJson)
            if (points.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "Нет точек. Добавь в fmagnit."
                    setTextColor(COLOR_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(6), 0, dp(6))
                })
            } else {
                points.forEach { (id, name) ->
                    val isActive = id == currentActiveId
                    val row = TextView(this).apply {
                        text = (if (isActive) "● " else "○ ") + name
                        setTextColor(
                            if (isActive) COLOR_ACCENT else COLOR_TEXT
                        )
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
            step("rebuildContent_points_done")

            val toggleBtn = TextView(this).apply {
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
                    try {
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
                    } catch (e: Throwable) {
                        CrashLogger.log(this@OverlayService, "MockToggle", e)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(10) }
            }
            container.addView(toggleBtn)

            val restoreBtn = TextView(this).apply {
                text = "ВОССТАНОВИТЬ GPS"
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = roundedBg(COLOR_INACTIVE, 12)
                setOnClickListener {
                    try {
                        MockManager.forceReset(this@OverlayService)
                        isRunning = false
                        OverlayPrefs.saveRunning(this@OverlayService, false)
                        Toast.makeText(this@OverlayService, "GPS восстановлен.", Toast.LENGTH_LONG).show()
                        rebuildContent()
                    } catch (e: Throwable) {
                        CrashLogger.log(this@OverlayService, "MockReset", e)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
            }
            container.addView(restoreBtn)
            step("rebuildContent_buttons_done")

            // Домофоны
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).also {
                    it.topMargin = dp(12)
                    it.bottomMargin = dp(8)
                }
                setBackgroundColor(0x22FFFFFF)
            })

            val doorsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val doorsCount = parseDoorPoints(currentDoorCodesJson).size
            val allCodesBtn = TextView(this).apply {
                text = "Все коды ($doorsCount)"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = roundedBg(COLOR_INACTIVE, 12)
                setOnClickListener {
                    val order = OrderTargetStore.load(this@OverlayService)
                    showAllDoorCodesDialog(order?.first ?: 0.0, order?.second ?: 0.0)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.rightMargin = dp(8)
                }
            }

            val addDoorBtn = TextView(this).apply {
                text = "GPS"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = roundedBg(COLOR_INACTIVE, 12)
                setOnClickListener { openAddDoorCodeDialog() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            doorsRow.addView(allCodesBtn)
            doorsRow.addView(addDoorBtn)
            container.addView(doorsRow)
            step("rebuildContent_done")
        } catch (e: Throwable) {
            CrashLogger.log(this, "rebuildContent", e)
        }
    }

    private fun addOrderBlock(
        container: LinearLayout,
        lat: Double,
        lon: Double
    ) {
        try {
            container.addView(TextView(this).apply {
                text = "Заказ клиента"
                setTextColor(COLOR_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            })

            container.addView(TextView(this).apply {
                text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(6))
            })

            val nearby = findNearbyDoorCodes(lat, lon)
            if (nearby.isNotEmpty()) {
                val codesText = nearby.joinToString(", ") { it.code }
                container.addView(TextView(this).apply {
                    text = "Возможный код домофона: $codesText"
                    setTextColor(COLOR_DOOR_OK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(10))
                })
            } else if (hasNearbyPointWithoutCode(lat, lon)) {
                container.addView(TextView(this).apply {
                    text = "Возможный код домофона: кода пока нет"
                    setTextColor(COLOR_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(10))
                })
            }

            container.addView(TextView(this).apply {
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

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val goBtn = TextView(this).apply {
                text = "ПЕРЕЙТИ"
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBg(COLOR_ACCENT, 12)
                setOnClickListener {
                    try {
                        val ok = MockManager.start(this@OverlayService, lat, lon, 5.0, 0.0, 1000L)
                        isRunning = ok
                        OverlayPrefs.saveRunning(this@OverlayService, ok)
                        rebuildContent()
                    } catch (e: Throwable) {
                        CrashLogger.log(this@OverlayService, "MockStartOrder", e)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.rightMargin = dp(6) }
            }

            val closeBtn = TextView(this).apply {
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
                    removeContentWindow()
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.leftMargin = dp(6) }
            }

            row.addView(goBtn)
            row.addView(closeBtn)
            container.addView(row)
        } catch (e: Throwable) {
            CrashLogger.log(this, "addOrderBlock", e)
        }
    }

    // ===================== ПАРСЕРЫ =====================

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
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parsePointsFullWithName(
        json: String
    ): List<WaypointTuple> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<WaypointTuple>(arr.length())
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                val name = obj.optString("name", "")
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val acc = obj.optDouble("accuracy", 3.0)
                out.add(WaypointTuple(id, name, lat, lon, acc))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    data class Quadruple(
        val first: String,
        val second: Double,
        val third: Double,
        val fourth: Double
    )

    data class WaypointTuple(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val accuracy: Double
    )

    private fun parsePoints(json: String): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(json)
            val out =
                ArrayList<Pair<String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "точка")
                if (id.isNotEmpty()) out.add(id to name)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun applyVisibility() {
        try {
            val h = headerWindowView ?: return
            val usageOk = ForegroundDetector.hasUsageAccess(this)
            val vis = if (!usageOk) {
                View.VISIBLE
            } else {
                if (OverlayBridge.isTargetAppForeground)
                    View.VISIBLE
                else
                    View.GONE
            }
            h.visibility = vis
            contentWindowView?.visibility = vis
        } catch (_: Throwable) {}
    }
}

const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"