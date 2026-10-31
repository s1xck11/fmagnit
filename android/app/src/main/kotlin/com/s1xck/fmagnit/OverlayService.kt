package com.s1xck.fmagnit

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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val ACTION_START = "com.s1xck.fmagnit.OVERLAY_START"
const val ACTION_POINT_START = "com.s1xck.fmagnit.POINT_START"
const val ACTION_POINT_STOP = "com.s1xck.fmagnit.POINT_STOP"

class OverlayService : Service() {
    companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "fmagnit_overlay"
        private const val COLOR_HEADER_BG = 0xFF1E1E1E.toInt()
        private const val COLOR_STROKE = 0xFF3D3D3D.toInt()
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
        private const val COLOR_ROUTE_LINE = 0xFF2196F3.toInt()
        private const val HEADER_HEIGHT_DP = 56
        private const val STATUS_FONT_SP = 12f
        private const val DOT_SIZE_SP = 14f
        private const val MY_LOCATION_UPDATE_INTERVAL_MS = 2000L
        const val ACTION_STOP = "com.s1xck.fmagnit.OVERLAY_STOP"
        const val ACTION_UPDATE_POINTS = "com.s1xck.fmagnit.OVERLAY_UPDATE_POINTS"
        const val ACTION_UPDATE_DOOR_CODES = "com.s1xck.fmagnit.OVERLAY_UPDATE_DOOR_CODES"
        const val ACTION_ORDER_TARGET = "com.s1xck.fmagnit.OVERLAY_ORDER_TARGET"
        const val ACTION_ORDER_TARGET_CLEAR = "com.s1xck.fmagnit.OVERLAY_ORDER_CLEAR"
        const val EXTRA_POINTS_JSON = "points_json"
        const val EXTRA_ACTIVE_ID = "active_id"
        const val EXTRA_DOOR_CODES_JSON = "door_codes_json"
        const val EXTRA_ORDER_LAT = "order_lat"
        const val EXTRA_ORDER_LON = "order_lon"
        @Volatile var currentPointsJson: String = "[]"
        @Volatile var currentActiveId: String? = null
        @Volatile var currentDoorCodesJson: String = "[]"
    }

    private var windowManager: WindowManager? = null
    private var headerWindowView: LinearLayout? = null
    private var headerWindowLp: WindowManager.LayoutParams? = null
    private var contentWindowView: LinearLayout? = null
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
    private var centerOnMeButton: TextView? = null
    private var myLocationMarker: Marker? = null
    private var myLocationTicker: Runnable? = null
    private var orientationTicker: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var routePolyline: Polyline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            initOsmdroid(); acquireWakeLock()
            currentPointsJson = OverlayPrefs.loadPoints(this)
            currentActiveId = OverlayPrefs.loadActiveId(this)
            currentDoorCodesJson = OverlayPrefs.loadDoorCodes(this)
            isRunning = OverlayPrefs.loadRunning(this)
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            OverlayBridge.setOverlayRunning(true)
            OverlayBridge.onOverlayStateChanged { handler.post { applyVisibility() } }
            buildHeaderWindow(); applyVisibility()
        } catch (e: Throwable) { CrashLogger.log(this, "OverlayService_onCreate", e); stopSelf() }
    }

    private fun initOsmdroid() {
        try {
            val cfg = Configuration.getInstance(); cfg.userAgentValue = packageName
            val base = File(cacheDir, "osmdroid"); if (!base.exists()) base.mkdirs()
            cfg.osmdroidBasePath = base
            val tiles = File(base, "tiles"); if (!tiles.exists()) tiles.mkdirs()
            cfg.osmdroidTileCache = tiles
        } catch (e: Exception) { CrashLogger.log(this, "initOsmdroid", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> { OverlayPrefs.saveServiceOn(this, false); stopSelf(); return START_NOT_STICKY }
                ACTION_UPDATE_POINTS -> {
                    intent.getStringExtra(EXTRA_POINTS_JSON)?.let { currentPointsJson = it; OverlayPrefs.savePoints(this, it) }
                    intent.getStringExtra(EXTRA_ACTIVE_ID)?.let { currentActiveId = it; OverlayPrefs.saveActiveId(this, it) }
                    handler.post { rebuildContent() }
                }
                ACTION_UPDATE_DOOR_CODES -> {
                    intent.getStringExtra(EXTRA_DOOR_CODES_JSON)?.let { currentDoorCodesJson = it; OverlayPrefs.saveDoorCodes(this, it) }
                    handler.post { rebuildContent() }
                }
                ACTION_ORDER_TARGET -> {
                    val lat = intent.getDoubleExtra(EXTRA_ORDER_LAT, Double.NaN)
                    val lon = intent.getDoubleExtra(EXTRA_ORDER_LON, Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) { OrderTargetStore.save(this, lat, lon); handler.post { openFullMap(lat, lon) } }
                }
                ACTION_ORDER_TARGET_CLEAR -> { OrderTargetStore.clear(this); handler.post { rebuildContent() } }
            }
        } catch (e: Throwable) { CrashLogger.log(this, "OverlayService_onStartCommand", e) }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            OverlayBridge.onOverlayStateChanged {}
            closeFullMap(); removeContentWindow()
            headerWindowView?.let { windowManager?.removeView(it) }
            headerWindowView = null
            OverlayBridge.setOverlayRunning(false); releaseWakeLock()
        } catch (e: Throwable) { CrashLogger.log(this, "OverlayService_onDestroy", e) }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fmagnit::overlay_wakelock")
            wakeLock?.setReferenceCounted(false); wakeLock?.acquire()
        } catch (e: Exception) { CrashLogger.log(this, "acquireWakeLock", e) }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { CrashLogger.log(this, "releaseWakeLock", e) }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "fmagnit overlay", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false); nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, com.google.android.apps.maps.MapsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("fmagnit").setContentText("Оверлей активен").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).setOngoing(true).build()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 1): GradientDrawable {
        val d = GradientDrawable(); d.shape = GradientDrawable.RECTANGLE; d.cornerRadius = dp(radiusDp).toFloat(); d.setColor(color)
        if (strokeColor != null) d.setStroke(dp(strokeWidthDp), strokeColor)
        return d
    }
    private fun windowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun getCurrentLocation(): Location? {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                val loc = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            }
            return best
        } catch (e: Exception) { return null }
    }

    private fun buildHeaderWindow() {
        val ctx = this; val screenW = resources.displayMetrics.widthPixels
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(16), 0); background = roundedBg(COLOR_HEADER_BG, 0); setOnClickListener { toggleExpanded() } }
        dotView = TextView(ctx).apply { text = "●"; setTextColor(COLOR_DOT_IDLE); setTextSize(TypedValue.COMPLEX_UNIT_SP, DOT_SIZE_SP); setPadding(0, 0, dp(12), 0) }
        activeLabel = TextView(ctx).apply { text = currentActiveNameOrPlaceholder(); setTextColor(COLOR_MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_FONT_SP); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        header.addView(dotView); header.addView(activeLabel); headerWindowView = header
        val headerWidth = (screenW * 0.65).toInt()
        headerWindowLp = WindowManager.LayoutParams(headerWidth, dp(HEADER_HEIGHT_DP), windowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        try { windowManager?.addView(header, headerWindowLp) } catch (e: Exception) { Toast.makeText(this, "Ошибка оверлея: ${e.message}", Toast.LENGTH_LONG).show() }
        applyDotColor()
    }

    private fun showContentWindow() {
        if (contentWindowView != null) { rebuildContent(); return }
        try {
            val ctx = this; val w = (resources.displayMetrics.widthPixels * 0.9).toInt()
            val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(COLOR_HEADER_BG, 16, COLOR_STROKE, 1); setPadding(dp(16), dp(12), dp(16), dp(16)) }
            val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(TextView(ctx).apply { text = "fmagnit"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            topRow.addView(TextView(ctx).apply { text = "×"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER; background = roundedBg(COLOR_ACCENT, 20); setPadding(dp(12), dp(0), dp(12), dp(4)); setOnClickListener { toggleExpanded() } })
            wrapper.addView(topRow)
            wrapper.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(8); it.bottomMargin = dp(8) }; setBackgroundColor(0x22FFFFFF) })
            contentContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrapper.addView(contentContainer); contentWindowView = wrapper
            contentWindowLp = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, windowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
            windowManager?.addView(wrapper, contentWindowLp); rebuildContent()
        } catch (e: Throwable) { CrashLogger.log(this, "showContentWindow", e) }
    }

    private fun removeContentWindow() {
        try { contentWindowView?.let { windowManager?.removeView(it) } } catch (e: Exception) { CrashLogger.log(this, "removeContentWindow", e) }
        contentWindowView = null; contentWindowLp = null; contentContainer = null
    }

    private fun toggleExpanded() { expanded = !expanded; if (expanded) showContentWindow() else removeContentWindow() }
    private fun currentActiveNameOrPlaceholder(): String {
        val id = currentActiveId ?: return "не выбрано"
        return parsePoints(currentPointsJson).firstOrNull { it.first == id }?.second ?: "не выбрано"
    }
    private fun applyDotColor() { dotView?.setTextColor(if (isRunning) COLOR_DOT_ACTIVE else COLOR_DOT_IDLE) }

    private fun createMapView(): MapView {
        val ctx = this; val map = MapView(ctx)
        try { map.setTileSource(TileSourceFactory.MAPNIK); map.setUseDataConnection(true); map.setMultiTouchControls(true); map.controller.setZoom(17.0); map.invalidate() } catch (e: Exception) { CrashLogger.log(this, "createMapView", e) }
        return map
    }

    private fun addOrderMarker(map: MapView, lat: Double, lon: Double) {
        try { val marker = Marker(map).apply { position = GeoPoint(lat, lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Заказ" }; map.overlays.add(marker) } catch (e: Exception) { CrashLogger.log(this, "addOrderMarker", e) }
    }

    private fun addWaypointMarker(map: MapView, lat: Double, lon: Double, name: String) {
        try {
            val marker = Marker(map).apply { position = GeoPoint(lat, lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setOnMarkerClickListener { _, _ -> true } }
            val sizePx = dp(28)
            val square = TextView(this).apply { text = "М"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; background = roundedBg(COLOR_MARKER_BG, 6); width = sizePx; height = sizePx }
            val bmp = viewToBitmap(square, sizePx, sizePx)
            if (bmp != null) { marker.icon = BitmapDrawable(resources, bmp); map.overlays.add(marker) }
            if (name.isNotEmpty()) {
                val label = Marker(map).apply { position = GeoPoint(lat, lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP); setOnMarkerClickListener { _, _ -> true } }
                val plate = TextView(this).apply { text = name; setTextColor(COLOR_PLATE_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setPadding(dp(6), dp(2), dp(6), dp(2)); background = roundedBg(COLOR_PLATE_BG, 4); maxLines = 1 }
                val bmpLabel = viewToBitmap(plate, dp(140), dp(22), exactWidth = false)
                if (bmpLabel != null) { label.icon = BitmapDrawable(resources, bmpLabel); map.overlays.add(label) }
            }
        } catch (e: Exception) { CrashLogger.log(this, "addWaypointMarker", e) }
    }

    private fun createMyLocationBitmap(): Bitmap? {
        return try {
            val sizePx = dp(28)
            val outerView = TextView(this).apply { background = roundedBg(COLOR_MY_LOCATION, 14, strokeColor = Color.WHITE, strokeWidthDp = 2); width = sizePx; height = sizePx }
            val outerSpec = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
            outerView.measure(outerSpec, outerSpec); outerView.layout(0, 0, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp); outerView.draw(canvas)
            val innerSize = dp(10)
            val innerView = TextView(this).apply { background = roundedBg(Color.WHITE, 5); width = innerSize; height = innerSize }
            val innerSpec = View.MeasureSpec.makeMeasureSpec(innerSize, View.MeasureSpec.EXACTLY)
            innerView.measure(innerSpec, innerSpec); innerView.layout(0, 0, innerSize, innerSize)
            val innerBmp = Bitmap.createBitmap(innerSize, innerSize, Bitmap.Config.ARGB_8888)
            innerView.draw(Canvas(innerBmp))
            canvas.drawBitmap(innerBmp, ((sizePx - innerSize) / 2).toFloat(), ((sizePx - innerSize) / 2).toFloat(), null)
            innerBmp.recycle(); bmp
        } catch (e: Exception) { CrashLogger.log(this, "createMyLocationBitmap", e); null }
    }

    private fun addOrUpdateMyLocationMarker(map: MapView, lat: Double, lon: Double) {
        try {
            if (myLocationMarker == null) {
                myLocationMarker = Marker(map).apply { position = GeoPoint(lat, lon); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setOnMarkerClickListener { _, _ -> true }; title = "Я здесь"
                    val bmp = createMyLocationBitmap(); if (bmp != null) icon = BitmapDrawable(resources, bmp); map.overlays.add(this) }
            } else { myLocationMarker?.position = GeoPoint(lat, lon) }
        } catch (e: Exception) { CrashLogger.log(this, "addOrUpdateMyLocationMarker", e) }
    }

    private fun viewToBitmap(view: View, widthPx: Int, heightPx: Int, exactWidth: Boolean = true): Bitmap? {
        return try {
            val wSpec = if (exactWidth) View.MeasureSpec.makeMeasureSpec(widthPx.coerceAtLeast(1), View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
            view.measure(wSpec, hSpec)
            val w = if (exactWidth) widthPx.coerceAtLeast(1) else view.measuredWidth.coerceAtLeast(1)
            val h = heightPx.coerceAtLeast(1)
            view.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp)); bmp
        } catch (e: Exception) { CrashLogger.log(this, "viewToBitmap", e); null }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0; val phi1 = Math.toRadians(lat1); val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1); val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun centerOnMyLocation() {
        try {
            val myLoc = getCurrentLocation()
            if (myLoc == null) { Toast.makeText(this, "Не удалось получить координаты", Toast.LENGTH_SHORT).show(); return }
            fullMap?.controller?.animateTo(GeoPoint(myLoc.latitude, myLoc.longitude))
            addOrUpdateMyLocationMarker(fullMap!!, myLoc.latitude, myLoc.longitude); fullMap?.invalidate()
        } catch (e: Exception) { CrashLogger.log(this, "centerOnMyLocation", e) }
    }
private fun openFullMap(lat: Double, lon: Double) {
        try {
            closeFullMap(); expanded = false; removeContentWindow()
            val ctx = this; fullMap = createMapView()
            fullMap?.let { map ->
                addOrderMarker(map, lat, lon)
                parsePointsFullWithName(currentPointsJson).forEach { (_, name, pLat, pLon, _) -> addWaypointMarker(map, pLat, pLon, name) }
                val myLoc = getCurrentLocation()
                if (myLoc != null) {
                    addOrUpdateMyLocationMarker(map, myLoc.latitude, myLoc.longitude)
                    Thread {
                        val route = RouteService.fetchRoute(myLoc.latitude, myLoc.longitude, lat, lon)
                        handler.post {
                            if (route != null && route.points.size > 1) {
                                routePolyline = Polyline(map).apply {
                                    outlinePaint.color = COLOR_ROUTE_LINE; outlinePaint.strokeWidth = 12f; outlinePaint.isAntiAlias = true
                                    route.points.forEach { addPoint(GeoPoint(it.first, it.second)) }
                                }
                                map.overlays.add(routePolyline)
                            }
                            map.invalidate()
                        }
                    }.start()
                }
                try { 
                    val rotation = RotationGestureOverlay(map)
                    rotation.isEnabled = true
                    map.overlays.add(rotation) 
                } catch (e: Exception) { CrashLogger.log(this, "openFullMap_rotation", e) }
                map.controller.setCenter(GeoPoint(lat, lon)); map.controller.setZoom(17.0); map.invalidate()
            }
            val container = FrameLayout(ctx)
            fullMap?.let { container.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            
            val closeBtn = TextView(ctx).apply { text = "×"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); gravity = Gravity.CENTER; background = roundedBg(COLOR_ACCENT, 22); setPadding(dp(14), dp(4), dp(14), dp(10)); setOnClickListener { closeFullMap() } }
            container.addView(closeBtn, FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(12); rightMargin = dp(12) })
            
            val north = TextView(ctx).apply { text = "N"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; background = roundedBg(COLOR_HEADER_BG, 20); setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE; setOnClickListener { fullMap?.mapOrientation = 0f; fullMap?.invalidate(); visibility = View.GONE } }
            resetNorthButton = north
            container.addView(north, FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(12); leftMargin = dp(12) })
            
            val meBtn = TextView(ctx).apply { text = "📍"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER; background = roundedBg(COLOR_MY_LOCATION, 22, strokeColor = Color.WHITE, strokeWidthDp = 2); setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { centerOnMyLocation() } }
            centerOnMeButton = meBtn
            container.addView(meBtn, FrameLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = dp(16); rightMargin = dp(16) })
            
            val currentOrientationTicker = orientationTicker
            if (currentOrientationTicker != null) { handler.post(currentOrientationTicker) } else {
                val newTicker = object : Runnable { override fun run() { try { val m = fullMap ?: return; resetNorthButton?.visibility = if (kotlin.math.abs(m.mapOrientation) > 1f) View.VISIBLE else View.GONE } catch (e: Exception) { CrashLogger.log(this@OverlayService, "orientationTicker", e) }; handler.postDelayed(this, 300L) } }
                orientationTicker = newTicker; handler.post(newTicker)
            }
            val currentLocationTicker = myLocationTicker
            if (currentLocationTicker != null) { handler.post(currentLocationTicker) } else {
                val newLocTicker = object : Runnable { override fun run() { try { val m = fullMap ?: return; val myLoc = getCurrentLocation(); if (myLoc != null) { addOrUpdateMyLocationMarker(m, myLoc.latitude, myLoc.longitude); m.invalidate() } } catch (e: Exception) { CrashLogger.log(this@OverlayService, "myLocationTicker", e) }; handler.postDelayed(this, MY_LOCATION_UPDATE_INTERVAL_MS) } }
                myLocationTicker = newLocTicker; handler.post(newLocTicker)
            }
            val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(COLOR_HEADER_BG, 16, COLOR_STROKE, 1); setPadding(dp(8), dp(8), dp(8), dp(8)); addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
            val dm = resources.displayMetrics
            fullMapLayoutParams = WindowManager.LayoutParams((dm.widthPixels * 0.9).toInt(), (dm.heightPixels * 0.85).toInt(), windowType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
            windowManager?.addView(wrapper, fullMapLayoutParams); fullMapWrapper = wrapper
        } catch (e: Throwable) { CrashLogger.log(this, "openFullMap", e); closeFullMap() }
    }

    private fun closeFullMap() {
        try {
            val ot = orientationTicker; if (ot != null) { handler.removeCallbacks(ot); orientationTicker = null }
            val lt = myLocationTicker; if (lt != null) { handler.removeCallbacks(lt); myLocationTicker = null }
            routePolyline?.let { fullMap?.overlays?.remove(it) }; routePolyline = null
            fullMapWrapper?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { CrashLogger.log(this, "closeFullMap_removeView", e) }
        fullMapWrapper = null; fullMapLayoutParams = null; resetNorthButton = null; centerOnMeButton = null; myLocationMarker = null
        try { fullMap?.onDetach() } catch (e: Exception) { CrashLogger.log(this, "closeFullMap_onDetach", e) }
        fullMap = null
    }

    private fun rebuildContent() {
        try {
            isRunning = try { MockManager.isRunning() } catch (e: Exception) { false }
            applyDotColor(); activeLabel?.text = currentActiveNameOrPlaceholder()
            val container = contentContainer ?: return; container.removeAllViews()
            val order = OrderTargetStore.load(this)
            if (order != null) {
                addOrderBlock(container, order.first, order.second)
                container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(4); it.bottomMargin = dp(8) }; setBackgroundColor(0x22FFFFFF) })
            }
            val points = parsePoints(currentPointsJson)
            if (points.isEmpty()) { container.addView(TextView(this).apply { text = "Нет точек. Добавь в fmagnit."; setTextColor(COLOR_MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(6), 0, dp(6)) }) } 
            else {
                points.forEach { (id, name) ->
                    val isActive = id == currentActiveId
                    val row = TextView(this).apply { text = (if (isActive) "● " else "○ ") + name; setTextColor(if (isActive) COLOR_ACCENT else COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(4), dp(10), dp(4), dp(10)); setOnClickListener { currentActiveId = id; OverlayPrefs.saveActiveId(this@OverlayService, id); activeLabel?.text = name; rebuildContent() } }
                    container.addView(row)
                }
            }
            val toggleBtn = TextView(this).apply { text = if (isRunning) "СТОП" else "СТАРТ"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12)); background = roundedBg(if (isRunning) COLOR_INACTIVE else COLOR_ACCENT, 12); setOnClickListener {
                try {
                    if (isRunning) { MockManager.stop(this@OverlayService); isRunning = false; OverlayPrefs.saveRunning(this@OverlayService, false) } 
                    else { val id = currentActiveId; if (id != null) { val point = parsePointsFull(currentPointsJson).firstOrNull { it.first == id }; if (point != null) { isRunning = MockManager.start(this@OverlayService, point.second, point.third, point.fourth, 0.0, 1000L); OverlayPrefs.saveRunning(this@OverlayService, isRunning) } } }
                    rebuildContent()
                } catch (e: Exception) { CrashLogger.log(this, "rebuildContent_toggleBtn", e) }
            }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) } }
            container.addView(toggleBtn)
            val restoreBtn = TextView(this).apply { text = "ВОССТАНОВИТЬ GPS"; setTextColor(COLOR_MUTED); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10)); background = roundedBg(COLOR_INACTIVE, 12); setOnClickListener { try { MockManager.forceReset(this@OverlayService); isRunning = false; OverlayPrefs.saveRunning(this@OverlayService, false); Toast.makeText(this@OverlayService, "GPS восстановлен.", Toast.LENGTH_LONG).show(); rebuildContent() } catch (e: Exception) { CrashLogger.log(this, "rebuildContent_restoreBtn", e) } }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) } }
            container.addView(restoreBtn)
        } catch (e: Throwable) { CrashLogger.log(this, "rebuildContent", e) }
    }

    private fun addOrderBlock(container: LinearLayout, lat: Double, lon: Double) {
        try {
            container.addView(TextView(this).apply { text = "Заказ клиента"; setTextColor(COLOR_ACCENT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)) })
            container.addView(TextView(this).apply { text = String.format(Locale.US, "%.6f, %.6f", lat, lon); setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, 0, 0, dp(6)) })
            val myLoc = getCurrentLocation()
            if (myLoc != null) {
                val dist = distanceMeters(myLoc.latitude, myLoc.longitude, lat, lon)
                val distText = if (dist < 1000) "${dist.toInt()} м" else String.format(Locale.US, "%.1f км", dist / 1000)
                container.addView(TextView(this).apply { text = "По прямой: ~$distText"; setTextColor(COLOR_DOOR_OK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(10)) })
            }
            val brouterBtnText = if (RouteService.isBRouterInstalled(this)) "🗺️ Открыть маршрут в BRouter" else "📥 Скачать BRouter для маршрутов"
            val brouterBtn = TextView(this).apply { text = brouterBtnText; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10)); background = roundedBg(COLOR_INACTIVE, 12); setOnClickListener { val intent = RouteService.getBRouterIntent(this@OverlayService); intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK; startActivity(intent) } }
            container.addView(brouterBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) })
            
            val openMapBtn = TextView(this).apply { text = "ОТКРЫТЬ КАРТУ"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12)); background = roundedBg(COLOR_INACTIVE, 12); setOnClickListener { openFullMap(lat, lon) } }
            container.addView(openMapBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) })
            
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val goBtn = TextView(this).apply { text = "ПЕРЕЙТИ"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12)); background = roundedBg(COLOR_ACCENT, 12); setOnClickListener { try { isRunning = MockManager.start(this@OverlayService, lat, lon, 5.0, 0.0, 1000L); OverlayPrefs.saveRunning(this@OverlayService, isRunning); rebuildContent() } catch (e: Exception) { CrashLogger.log(this, "addOrderBlock_goBtn", e) } } }
            goBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = dp(6) }
            
            val closeBtn = TextView(this).apply { text = "ЗАКРЫТЬ"; setTextColor(COLOR_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12)); background = roundedBg(COLOR_INACTIVE, 12); setOnClickListener { OrderTargetStore.clear(this@OverlayService); expanded = false; removeContentWindow() } }
            closeBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = dp(6) }
            
            row.addView(goBtn); row.addView(closeBtn); container.addView(row)
        } catch (e: Exception) { CrashLogger.log(this, "addOrderBlock", e) }
    }

    private fun parsePointsFull(json: String): List<Quadruple> {
        return try { val arr = JSONArray(json); val out = ArrayList<Quadruple>(arr.length()); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val id = obj.optString("id", ""); if (id.isEmpty()) continue; out.add(Quadruple(id, obj.optDouble("latitude", 0.0), obj.optDouble("longitude", 0.0), obj.optDouble("accuracy", 3.0))) }; out } catch (e: Exception) { CrashLogger.log(this, "parsePointsFull", e); emptyList() }
    }
    private fun parsePointsFullWithName(json: String): List<WaypointTuple> {
        return try { val arr = JSONArray(json); val out = ArrayList<WaypointTuple>(arr.length()); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val id = obj.optString("id", ""); if (id.isEmpty()) continue; val lat = obj.optDouble("latitude", Double.NaN); val lon = obj.optDouble("longitude", Double.NaN); if (lat.isNaN() || lon.isNaN()) continue; out.add(WaypointTuple(id, obj.optString("name", ""), lat, lon, obj.optDouble("accuracy", 3.0))) }; out } catch (e: Exception) { CrashLogger.log(this, "parsePointsFullWithName", e); emptyList() }
    }
    data class Quadruple(val first: String, val second: Double, val third: Double, val fourth: Double)
    data class WaypointTuple(val id: String, val name: String, val lat: Double, val lon: Double, val accuracy: Double)
    private fun parsePoints(json: String): List<Pair<String, String>> {
        return try { val arr = JSONArray(json); val out = ArrayList<Pair<String, String>>(arr.length()); for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); val id = obj.optString("id", ""); if (id.isNotEmpty()) out.add(id to obj.optString("name", "точка")) }; out } catch (e: Exception) { CrashLogger.log(this, "parsePoints", e); emptyList() }
    }
    private fun applyVisibility() {
        try { val h = headerWindowView ?: return; val usageOk = ForegroundDetector.hasUsageAccess(this); val vis = if (!usageOk) View.VISIBLE else if (OverlayBridge.isTargetAppForeground) View.VISIBLE else View.GONE; h.visibility = vis; contentWindowView?.visibility = vis } catch (e: Exception) { CrashLogger.log(this, "applyVisibility", e) }
    }
}