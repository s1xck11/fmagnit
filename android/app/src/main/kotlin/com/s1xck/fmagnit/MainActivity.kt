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

class MainActivity : FlutterActivity() {

    private val mockChannelName = "com.s1xck.fmagnit/mock_location"
    private val overlayChannelName = "com.s1xck.fmagnit/overlay"
    private val providerName = LocationManager.GPS_PROVIDER

    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null

    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentAcc: Double = 3.0
    private var currentAlt: Double = 0.0

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

    private fun ensureProvider() {
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

    private fun buildLocation(lat: Double, lon: Double, acc: Double, alt: Double): Location {
        val loc = Location(providerName)
        loc.latitude = lat
        loc.longitude = lon
        loc.accuracy = acc.toFloat()
        loc.altitude = alt
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.verticalAccuracyMeters = acc.toFloat()
            loc.speedAccuracyMetersPerSecond = acc.toFloat()
            loc.bearingAccuracyDegrees = acc.toFloat()
        }
        loc.speed = 0f
        loc.bearing = 0f
        return loc
    }

    private fun pushLocation() {
        val lm = locationManager ?: return
        try { lm.setTestProviderLocation(providerName, buildLocation(currentLat, currentLon, currentAcc, currentAlt)) } catch (_: Exception) {}
    }

    private fun startMock(lat: Double, lon: Double, acc: Double, alt: Double, intervalMs: Long): Boolean {
        return try {
            currentLat = lat; currentLon = lon; currentAcc = acc; currentAlt = alt
            ensureProvider(); pushLocation(); startPushLoop(intervalMs); true
        } catch (_: Exception) { false }
    }

    private fun startPushLoop(intervalMs: Long) {
        stopPushLoop()
        val r = object : Runnable {
            override fun run() { pushLocation(); handler?.postDelayed(this, intervalMs) }
        }
        pushRunnable = r
        handler?.postDelayed(r, intervalMs)
    }

    private fun stopPushLoop() {
        pushRunnable?.let { handler?.removeCallbacks(it) }
        pushRunnable = null
    }

    private fun updateMock(lat: Double, lon: Double, acc: Double, alt: Double): Boolean {
        return try {
            currentLat = lat; currentLon = lon; currentAcc = acc; currentAlt = alt
            pushLocation(); true
        } catch (_: Exception) { false }
    }

    private fun stopMock(): Boolean {
        return try {
            stopPushLoop()
            try { locationManager?.setTestProviderEnabled(providerName, false) } catch (_: Exception) {}
            try { locationManager?.removeTestProvider(providerName) } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

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