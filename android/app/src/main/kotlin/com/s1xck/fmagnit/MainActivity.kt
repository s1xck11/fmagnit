package com.s1xck.fmagnit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : FlutterActivity() {

    private val mockChannelName = "com.s1xck.fmagnit/mock_location"
    private val overlayChannelName = "com.s1xck.fmagnit/overlay"

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null

    // Базовая точка (куда мы «телепортируемся»)
    private var baseLat: Double = 0.0
    private var baseLon: Double = 0.0
    private var baseAcc: Double = 3.0
    private var baseAlt: Double = 0.0

    // Текущая отображаемая точка (с шумом)
    private var displayLat: Double = 0.0
    private var displayLon: Double = 0.0

    private var isMockRunning = false

    private var pointBroadcastReceiver: BroadcastReceiver? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        locationManager =
            applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        handler = Handler(Looper.getMainLooper())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, mockChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isMockLocationEnabled" -> result.success(isMockLocationEnabled())
                    "openDeveloperSettings" -> { openDeveloperSettings(); result.success(null) }
                    "startMock" -> {
                        val lat = call.argument<Double>("latitude") ?: 0.0
                        val lon = call.argument<Double>("longitude") ?: 0.0
                        val acc = call.argument<Double>("accuracy") ?: 3.0
                        val alt = call.argument<Double>("altitude") ?: 0.0
                        val interval = call.argument<Int>("intervalMs") ?: 1000
                        result.success(startMock(lat, lon, acc, alt, interval.toLong()))
                    }
                    "updateMock" -> {
                        val lat = call.argument<Double>("latitude") ?: 0.0
                        val lon = call.argument<Double>("longitude") ?: 0.0
                        val acc = call.argument<Double>("accuracy") ?: 3.0
                        val alt = call.argument<Double>("altitude") ?: 0.0
                        result.success(updateMock(lat, lon, acc, alt))
                    }
                    "stopMock" -> result.success(stopMock())
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, overlayChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                    "requestOverlayPermission" -> { requestOverlayPermission(); result.success(null) }
                    "hasAccessibilityPermission" -> result.success(isAccessibilityServiceEnabled())
                    "openAccessibilitySettings" -> { openAccessibilitySettings(); result.success(null) }
                    "hasUsageAccess" -> result.success(ForegroundDetector.hasUsageAccess(this))
                    "openUsageAccessSettings" -> { openUsageAccessSettings(); result.success(null) }
                    "getForegroundPackage" -> result.success(ForegroundDetector.getForegroundPackage(this))
                    "startOverlay" -> { startOverlayService(); result.success(true) }
                    "stopOverlay" -> { stopOverlayService(); result.success(true) }
                    "isOverlayRunning" -> result.success(OverlayBridge.isOverlayServiceRunning)
                    "updatePoints" -> {
                        val json = call.argument<String>("pointsJson") ?: "[]"
                        val activeId = call.argument<String>("activeId")
                        updateOverlayPoints(json, activeId)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        pointBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_POINT_START -> {
                        val id = intent.getStringExtra("id")
                        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, overlayChannelName)
                            .invokeMethod("onOverlayStart", mapOf("id" to id))
                    }
                    ACTION_POINT_STOP -> {
                        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, overlayChannelName)
                            .invokeMethod("onOverlayStop", null)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_POINT_START)
            addAction(ACTION_POINT_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pointBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pointBroadcastReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { pointBroadcastReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        stopPushLoop()
        super.onDestroy()
    }

    // ---------- Разрешения ----------

    private fun isMockLocationEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                        android.os.Process.myUid(), packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                        android.os.Process.myUid(), packageName
                    )
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getString(contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION) == "1"
            }
        } catch (_: Exception) { false }
    }

    private fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ---------- Mock: провайдеры ----------

    private fun ensureProvider(providerName: String) {
        val lm = locationManager ?: return
        try { lm.removeTestProvider(providerName) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lm.addTestProvider(
                    providerName, false, false, false, false, true, true, true,
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    providerName, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
            }
            lm.setTestProviderEnabled(providerName, true)
        } catch (_: Exception) {}
    }

    private fun buildLocation(
        providerName: String,
        lat: Double,
        lon: Double,
        acc: Double,
        alt: Double,
        speed: Float,
        bearing: Float
    ): Location {
        val loc = Location(providerName)
        loc.latitude = lat
        loc.longitude = lon
        loc.accuracy = acc.toFloat()
        loc.altitude = alt
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.verticalAccuracyMeters = acc.toFloat()
            loc.speedAccuracyMetersPerSecond = (acc * 0.4).toFloat()
            loc.bearingAccuracyDegrees = 20f
        }
        loc.speed = speed
        loc.bearing = bearing
        return loc
    }

    // ---------- Джиттер (реалистичное дрожание GPS) ----------

    /**
     * Небольшой случайный сдвиг от базы. Максимальное отклонение
     * — около [maxOffsetMeters]. Распределение — нормальное-ish,
     * но простое: сумма двух равномерных.
     */
    private fun applyJitter(offsetMeters: Double = 2.5) {
        // Отклонение в метрах (в среднем меньше 2-3 м, изредка больше)
        val r = Random.nextDouble(-1.0, 1.0) + Random.nextDouble(-1.0, 1.0)
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val dx = r * offsetMeters * cos(angle)
        val dy = r * offsetMeters * sin(angle)

        // Перевод метров в градусы (приблизительно)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

        displayLat = baseLat + dy / metersPerDegLat
        displayLon = baseLon + dx / metersPerDegLon
    }

    private fun pushLocation() {
        val lm = locationManager ?: return
        if (!isMockRunning) return

        // Генерируем новый джиттер
        applyJitter()

        // Точность плавает вокруг базовой: ±3 м
        val jitterAcc = baseAcc + Random.nextDouble(-3.0, 3.0)
        val acc = jitterAcc.coerceAtLeast(1.0)

        // Микро-скорость и курс — как у реального GPS в покое
        val speed = Random.nextFloat() * 0.4f       // 0 – 0.4 м/с
        val bearing = Random.nextFloat() * 360f     // любой курс

        try {
            for (p in providers) {
                val loc = buildLocation(p, displayLat, displayLon, acc, baseAlt, speed, bearing)
                try { lm.setTestProviderLocation(p, loc) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------- Старт / стоп мока ----------

    private fun startMock(lat: Double, lon: Double, acc: Double, alt: Double, intervalMs: Long): Boolean {
        return try {
            baseLat = lat
            baseLon = lon
            baseAcc = acc
            baseAlt = alt

            displayLat = lat
            displayLon = lon

            for (p in providers) ensureProvider(p)

            isMockRunning = true
            pushLocation()
            startPushLoop(intervalMs)
            true
        } catch (_: Exception) { false }
    }

    private fun startPushLoop(baseIntervalMs: Long) {
        stopPushLoop()
        val r = object : Runnable {
            override fun run() {
                pushLocation()
                // Случайный интервал: ±30% от базового
                val jitter = baseIntervalMs * (0.7 + Random.nextDouble() * 0.6)
                handler?.postDelayed(this, jitter.toLong())
            }
        }
        pushRunnable = r
        handler?.postDelayed(r, baseIntervalMs)
    }

    private fun stopPushLoop() {
        pushRunnable?.let { handler?.removeCallbacks(it) }
        pushRunnable = null
    }

    private fun updateMock(lat: Double, lon: Double, acc: Double, alt: Double): Boolean {
        return try {
            baseLat = lat
            baseLon = lon
            baseAcc = acc
            baseAlt = alt
            displayLat = lat
            displayLon = lon
            pushLocation()
            true
        } catch (_: Exception) { false }
    }

    private fun stopMock(): Boolean {
        return try {
            isMockRunning = false
            stopPushLoop()
            for (p in providers) {
                try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}
                try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) { false }
    }

    // ---------- Overlay ----------

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${MainAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(service, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun startOverlayService() {
        if (OverlayBridge.isOverlayServiceRunning) {
            updateOverlayPoints(OverlayService.currentPointsJson, OverlayService.currentActiveId)
            return
        }
        OverlayPrefs.saveServiceOn(this, true)
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlayService() {
        OverlayPrefs.saveServiceOn(this, false)
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateOverlayPoints(json: String, activeId: String?) {
        OverlayService.currentPointsJson = json
        if (activeId != null) OverlayService.currentActiveId = activeId
        OverlayPrefs.savePoints(this, json)
        if (activeId != null) OverlayPrefs.saveActiveId(this, activeId)
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_POINTS
            putExtra(OverlayService.EXTRA_POINTS_JSON, json)
            if (activeId != null) putExtra(OverlayService.EXTRA_ACTIVE_ID, activeId)
        }
        try { startService(intent) } catch (_: Exception) {}
    }
}