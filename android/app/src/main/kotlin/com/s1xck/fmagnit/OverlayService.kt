package com.s1xck.fmagnit

import android.app.AlertDialog
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
import android.os.PowerManager
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
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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

    // Окно шапки (прижато к левому верхнему углу)
    private var headerWindowView: LinearLayout? = null
    private var headerWindowLp: WindowManager.LayoutParams? = null

    // Окно контента (по центру, с кнопкой ×)
    private var contentWindowView: LinearLayout? = null
    private var contentWindowLp: WindowManager.LayoutParams? = null
    private var contentContainer: LinearLayout? = null

    private var activeLabel: TextView? = null
    private var dotView: TextView? = null

    private var expanded = false
    private var doorsExpanded = false
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
        initOsmdroid()
        acquireWakeLock()

        currentPointsJson = OverlayPrefs.loadPoints(this)
        currentActiveId = OverlayPrefs.loadActiveId(this)
        currentDoorCodesJson = OverlayPrefs.loadDoorCodes(this)
        isRunning = OverlayPrefs.loadRunning(this)

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        OverlayBridge.setOverlayRunning(true)
        OverlayBridge.onOverlayStateChanged {
            handler.post { applyVisibility() }
        }

        buildHeaderWindow()
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
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
        removeContentWindow()
        try {
            headerWindowView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        headerWindowView = null
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
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
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

    // ===================== ОКНО ШАПКИ =====================

    private fun buildHeaderWindow() {
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

        try {
            windowManager?.addView(header, headerWindowLp)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Ошибка оверлея: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }

        applyDotColor()
    }

    // ===================== ОКНО КОНТЕНТА =====================

    private fun showContentWindow() {
        if (contentWindowView != null) {
            rebuildContent()
            return
        }

        val ctx = this
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.9).toInt()

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_HEADER_BG, 16)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // Заголовок окошка + кнопка ×
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
        wrapper.addView(topRow)

        wrapper.addView(View(ctx).apply {
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
        wrapper.addView(contentContainer)

        contentWindowView = wrapper

        contentWindowLp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(wrapper, contentWindowLp)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Ошибка окна: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            contentWindowView = null
            contentWindowLp = null
            contentContainer = null
            return
        }

        rebuildContent()
    }

    private fun removeContentWindow() {
        try {
            contentWindowView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
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

    private fun addOrderMarker(map: MapView, lat: Double, lon: Double) {
        try {
            val marker = Marker(map)
            marker.position = GeoPoint(lat, lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Заказ"
            map.overlays.add(marker)
        } catch (_: Exception) {}
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
            marker.icon = android.graphics.drawable.BitmapDrawable(
                resources,
                viewToBitmap(square, sizePx, sizePx)
            )

            map.overlays.add(marker)

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
                label.icon = android.graphics.drawable.BitmapDrawable(
                    resources,
                    viewToBitmap(plate, wPx, hPx, exactWidth = false)
                )
                map.overlays.add(label)
            }
        } catch (_: Exception) {}
    }

    private fun viewToBitmap(
        view: View,
        widthPx: Int,
        heightPx: Int,
        exactWidth: Boolean = true
    ): android.graphics.Bitmap {
        val wSpec = if (exactWidth)
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        else
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST)
        view.measure(wSpec, hSpec)
        val w = if (exactWidth) widthPx else view.measuredWidth.coerceAtLeast(1)
        val h = heightPx
        view.layout(0, 0, w, h)
        val bmp = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        view.draw(canvas)
        return bmp
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            false
        }
    }

    private fun reloadDoorCodesFromFile() {
        try {
            currentDoorCodesJson = DoorCodeNativeStore.asJsonArrayString(this)
            OverlayPrefs.saveDoorCodes(this, currentDoorCodesJson)
        } catch (_: Exception) {}
    }

    // ===================== GPS-ДИАЛОГ =====================

    private fun openAddDoorCodeDialog() {
        val last: android.location.Location? = try {
            MockManager.getBestRealLocation(this)
        } catch (_: Exception) {
            null
        }

        if (last == null) {
            Toast.makeText(
                this,
                "Нет координат. Включи GPS или запусти mock.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val lat = last.latitude
        val lon = last.longitude

        val ctx = this
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val addrEt = EditText(ctx).apply {
            hint = "Адрес или название"
            setSingleLine(true)
        }
        val codeEt = EditText(ctx).apply {
            hint = "Код домофона (например, 1234#)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val coordsTv = TextView(ctx).apply {
            text = String.format(
                Locale.US,
                "Координаты: %.6f, %.6f",
                lat, lon
            )
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        }

        wrapper.addView(addrEt)
        wrapper.addView(codeEt)
        wrapper.addView(coordsTv)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Новый код домофона")
            .setView(wrapper)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .create()

        dialog.window?.setType(windowType())

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val addr = addrEt.text.toString().trim()
                    val code = codeEt.text.toString().trim()
                    if (code.isEmpty()) {
                        codeEt.error = "Введи код"
                        return@setOnClickListener
                    }
                    val newId = DoorCodeNativeStore.add(
                        ctx, code, addr, lat, lon
                    )
                    if (newId != null) {
                        reloadDoorCodesFromFile()
                        val bcast = Intent(ACTION_DOOR_CODES_CHANGED)
                        bcast.setPackage(packageName)
                        try { sendBroadcast(bcast) } catch (_: Exception) {}
                        dialog.dismiss()
                        rebuildContent()
                        Toast.makeText(
                            ctx, "Код сохранён", Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            ctx, "Не удалось сохранить", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }

        dialog.show()
    }

    // ===================== ПОЛНАЯ КАРТА =====================

    private fun openFullMap(lat: Double, lon: Double) {
        closeFullMap()

        // Закрываем окошко контента, шапка остаётся
        expanded = false
        removeContentWindow()

        val ctx = this

        fullMap = createMapView()
        fullMap?.let { map ->
            addOrderMarker(map, lat, lon)

            val pts = parsePointsFullWithName(currentPointsJson)
            pts.forEach { (_, name, pLat, pLon, _) ->
                addWaypointMarker(map, pLat, pLon, name)
            }

            try {
                val rotation = RotationGestureOverlay(map)
                rotation.isEnabled = true
                map.overlays.add(rotation)
            } catch (_: Exception) {}

            map.controller.setCenter(GeoPoint(lat, lon))
            map.controller.setZoom(17.0)
            map.invalidate()
        }

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
                    resetNorthButton?.visibility =
                        if (visible) View.VISIBLE else View.GONE
                } catch (_: Exception) {}
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

        try {
            windowManager?.addView(wrapper, fullMapLayoutParams)
            fullMapWrapper = wrapper
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Ошибка карты: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            closeFullMap()
        }
    }

    private fun closeFullMap() {
        try {
            fullMapWrapper?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        fullMapWrapper = null
        fullMapLayoutParams = null
        resetNorthButton = null
        try { fullMap?.onDetach() } catch (_: Exception) {}
        fullMap = null
    }

    // ===================== КОНТЕНТ ОКНА =====================

    private fun rebuildContent() {
        isRunning = MockManager.isRunning()
        applyDotColor()
        activeLabel?.text = currentActiveNameOrPlaceholder()

        val container = contentContainer ?: return
        container.removeAllViews()

        // Секция заказа
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

        // Точки
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

        // СТАРТ / СТОП
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
                            OverlayPrefs.saveRunning(
                                this@OverlayService, ok
                            )
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

        // ВОССТАНОВИТЬ GPS
        val restoreBtn = TextView(this).apply {
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

        // ДОМОФОНЫ
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).also {
                it.topMargin = dp(12)
                it.bottomMargin = dp(4)
            }
            setBackgroundColor(0x22FFFFFF)
        })

        val doorsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                doorsExpanded = !doorsExpanded
                rebuildContent()
            }
        }
        doorsHeader.addView(TextView(this).apply {
            text = "Домофоны"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        doorsHeader.addView(TextView(this).apply {
            text = if (doorsExpanded) "▲" else "▼"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, dp(8), 0)
        })

        doorsHeader.addView(TextView(this).apply {
            text = "📍"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setOnClickListener { openAddDoorCodeDialog() }
        })
        container.addView(doorsHeader)

        if (doorsExpanded) {
            val doors = parseDoorPoints(currentDoorCodesJson)
            if (doors.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "Пока нет кодов. Нажми 📍 чтобы добавить."
                    setTextColor(COLOR_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                })
            } else {
                val listWrapper = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                doors.forEach { d ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    }
                    row.addView(TextView(this).apply {
                        text = d.code
                        setTextColor(COLOR_TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    if (d.address.isNotEmpty()) {
                        row.addView(TextView(this).apply {
                            text = d.address
                            setTextColor(COLOR_MUTED)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                    listWrapper.addView(row)
                }
                val scroll = ScrollView(this)
                scroll.addView(listWrapper)
                container.addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(180)
                    )
                )
            }
        }
    }

    private fun addOrderBlock(
        container: LinearLayout,
        lat: Double,
        lon: Double
    ) {
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
                val ok = MockManager.start(
                    this@OverlayService, lat, lon, 5.0, 0.0, 1000L
                )
                isRunning = ok
                OverlayPrefs.saveRunning(this@OverlayService, ok)
                rebuildContent()
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applyVisibility() {
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
    }
}

const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"