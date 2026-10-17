package com.google.android.apps.maps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.s1xck.fmagnit.ACTION_POINT_START
import com.s1xck.fmagnit.ACTION_POINT_STOP
import com.s1xck.fmagnit.CrashLogger
import com.s1xck.fmagnit.ForegroundDetector
import com.s1xck.fmagnit.MainAccessibilityService
import com.s1xck.fmagnit.MapDownloadService
import com.s1xck.fmagnit.MockManager
import com.s1xck.fmagnit.OrderTargetStore
import com.s1xck.fmagnit.OverlayBridge
import com.s1xck.fmagnit.OverlayPrefs
import com.s1xck.fmagnit.OverlayService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.osmdroid.config.Configuration

class MapsActivity : FlutterActivity() {

    companion object {
        private const val TAG = "FMAGNIT_INTENT"
    }

    private val mockChannelName = "com.s1xck.fmagnit/mock_location"
    private val overlayChannelName = "com.s1xck.fmagnit/overlay"

    private var handler: Handler? = null

    private var pointBroadcastReceiver: BroadcastReceiver? = null
    private var overlayChannel: MethodChannel? = null
    private var mockChannel: MethodChannel? = null

    private var lastHandledUri: String? = null
    private var isTransparentLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val action = intent?.action
            val data = intent?.data
            if (action == Intent.ACTION_VIEW && data != null) {
                isTransparentLaunch = true
                setTheme(android.R.style.Theme_Translucent_NoTitleBar)
            }
        } catch (_: Exception) {}
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        try {
            super.configureFlutterEngine(flutterEngine)
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "configureFlutterEngine", e)
            throw e
        }

        try {
            try {
                Configuration.getInstance().userAgentValue = packageName
            } catch (_: Throwable) {}

            handler = Handler(Looper.getMainLooper())

            mockChannel = MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                mockChannelName
            )
            mockChannel?.setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "isMockLocationEnabled" -> {
                            result.success(MockManager.isMockLocationEnabled(this))
                        }
                        "openDeveloperSettings" -> {
                            openDeveloperSettings()
                            result.success(null)
                        }
                        "isMockRunning" -> result.success(MockManager.isRunning())
                        "startMock" -> {
                            val lat = call.argument<Double>("latitude") ?: 0.0
                            val lon = call.argument<Double>("longitude") ?: 0.0
                            val acc = call.argument<Double>("accuracy") ?: 3.0
                            val alt = call.argument<Double>("altitude") ?: 0.0
                            val interval = call.argument<Int>("intervalMs") ?: 1000
                            result.success(
                                MockManager.start(this, lat, lon, acc, alt, interval.toLong())
                            )
                        }
                        "updateMock" -> {
                            val lat = call.argument<Double>("latitude") ?: 0.0
                            val lon = call.argument<Double>("longitude") ?: 0.0
                            val acc = call.argument<Double>("accuracy") ?: 3.0
                            val alt = call.argument<Double>("altitude") ?: 0.0
                            result.success(MockManager.update(this, lat, lon, acc, alt))
                        }
                        "stopMock" -> result.success(MockManager.stop(this))
                        "forceResetMock" -> result.success(MockManager.forceReset(this))
                        "getRealLocation" -> {
                            val loc = MockManager.getBestRealLocation(this)
                            if (loc == null) {
                                result.success(null)
                            } else {
                                result.success(mapOf(
                                    "latitude" to loc.latitude,
                                    "longitude" to loc.longitude,
                                    "accuracy" to loc.accuracy.toDouble(),
                                    "ageMs" to (System.currentTimeMillis() - loc.time)
                                ))
                            }
                        }
                        "getLastOrderTarget" -> result.success(OrderTargetStore.loadLast(this))
                        "getLastCrash" -> result.success(CrashLogger.load(this))
                        "clearCrash" -> {
                            CrashLogger.clear(this)
                            result.success(true)
                        }
                        "startMapDownload" -> {
                            val name = call.argument<String>("name") ?: "Регион"
                            val south = call.argument<Double>("south") ?: 0.0
                            val north = call.argument<Double>("north") ?: 0.0
                            val west = call.argument<Double>("west") ?: 0.0
                            val east = call.argument<Double>("east") ?: 0.0
                            val minZoom = call.argument<Int>("minZoom") ?: 12
                            val maxZoom = call.argument<Int>("maxZoom") ?: 17
                            MapDownloadService.start(
                                applicationContext, name, south, north, west, east, minZoom, maxZoom
                            )
                            result.success(true)
                        }
                        "getMapDownloadProgress" -> {
                            result.success(MapDownloadService.progressMap())
                        }
                        "cancelMapDownload" -> {
                            MapDownloadService.cancel(applicationContext)
                            result.success(true)
                        }
                        "getDownloadedRegions" -> {
                            result.success(MapDownloadService.listRegions(applicationContext))
                        }
                        "deleteDownloadedRegion" -> {
                            val id = call.argument<String>("id") ?: ""
                            MapDownloadService.deleteRegion(applicationContext, id)
                            result.success(true)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Throwable) {
                    CrashLogger.log(applicationContext, "mock_channel:${call.method}", e)
                    result.error("ERR", e.message, null)
                }
            }

            overlayChannel = MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                overlayChannelName
            )
            overlayChannel?.setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                        "requestOverlayPermission" -> {
                            requestOverlayPermission()
                            result.success(null)
                        }
                        "hasAccessibilityPermission" -> result.success(isAccessibilityServiceEnabled())
                        "openAccessibilitySettings" -> {
                            openAccessibilitySettings()
                            result.success(null)
                        }
                        "hasUsageAccess" -> result.success(ForegroundDetector.hasUsageAccess(this))
                        "openUsageAccessSettings" -> {
                            openUsageAccessSettings()
                            result.success(null)
                        }
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
                        "clearOrderTarget" -> {
                            clearOrderTarget()
                            result.success(true)
                        }
                        "setManualOrderTarget" -> {
                            val lat = call.argument<Double>("latitude") ?: 0.0
                            val lon = call.argument<Double>("longitude") ?: 0.0
                            setManualOrderTarget(lat, lon)
                            result.success(true)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Throwable) {
                    CrashLogger.log(applicationContext, "overlay_channel:${call.method}", e)
                    result.error("ERR", e.message, null)
                }
            }

            pointBroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Больше не ловим mock-команды — они идут напрямую в MockManager
                    try {
                        when (intent?.action) {
                            ACTION_POINT_START -> {
                                // Не используется, но оставим для совместимости
                            }
                            ACTION_POINT_STOP -> {
                                // Не используется
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
            // Ресивер оставляем зарегистрированным (на всякий случай), но ничего не делаем
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
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "configureFlutterEngine_body", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val delay = if (isTransparentLaunch) 200L else 500L
        handler?.postDelayed({
            handleIncomingIntent(intent)
        }, delay)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            val action = intent?.action
            val data: Uri? = intent?.data

            if (action == Intent.ACTION_MAIN || data == null) return

            val uriStr = data.toString()
            if (uriStr == lastHandledUri) return

            val parsed = parseCoordinates(data)
            if (parsed == null) {
                lastHandledUri = uriStr
                if (isTransparentLaunch) finish()
                return
            }

            val (lat, lon) = parsed
            lastHandledUri = uriStr

            OrderTargetStore.save(applicationContext, lat, lon, data.toString())

            if (OverlayBridge.isOverlayServiceRunning) {
                val svc = Intent(this, OverlayService::class.java).apply {
                    setAction(OverlayService.ACTION_ORDER_TARGET)
                    putExtra(OverlayService.EXTRA_ORDER_LAT, lat)
                    putExtra(OverlayService.EXTRA_ORDER_LON, lon)
                }
                try { startService(svc) } catch (_: Exception) {}
            }

            val bcast = Intent(ACTION_ORDER_TARGET_UPDATED)
            bcast.setPackage(packageName)
            bcast.putExtra("lat", lat)
            bcast.putExtra("lon", lon)
            sendBroadcast(bcast)

            if (isTransparentLaunch) finish()
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "handleIncomingIntent_CRASH", e)
            if (isTransparentLaunch) finish()
        }
    }

    private fun setManualOrderTarget(lat: Double, lon: Double) {
        try {
            OrderTargetStore.save(applicationContext, lat, lon, "manual://test")
            if (OverlayBridge.isOverlayServiceRunning) {
                val svc = Intent(this, OverlayService::class.java).apply {
                    setAction(OverlayService.ACTION_ORDER_TARGET)
                    putExtra(OverlayService.EXTRA_ORDER_LAT, lat)
                    putExtra(OverlayService.EXTRA_ORDER_LON, lon)
                }
                try { startService(svc) } catch (_: Exception) {}
            }
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "setManualOrderTarget", e)
        }
    }

    private fun parseCoordinates(uri: Uri): Pair<Double, Double>? {
        val scheme = uri.scheme ?: return null
        when (scheme.lowercase()) {
            "google.navigation" -> {
                val ssp = uri.schemeSpecificPart ?: return null
                val idx = ssp.indexOf('=')
                val coords = if (idx >= 0) ssp.substring(idx + 1).substringBefore("&") else ssp
                return parseLatLonString(coords)
            }
            "geo" -> {
                val ssp = uri.schemeSpecificPart ?: return null
                val coordsPart = ssp.substringBefore("?")
                parseLatLonString(coordsPart)?.let { return it }
                val idx = ssp.indexOf("q=")
                if (idx >= 0) {
                    val coords = ssp.substring(idx + 2).substringBefore("&")
                    parseLatLonString(coords)?.let { return it }
                }
                return null
            }
            "https", "http" -> {
                try {
                    uri.getQueryParameter("destination")?.let {
                        parseLatLonString(it)?.let { r -> return r }
                    }
                    uri.getQueryParameter("q")?.let {
                        parseLatLonString(it)?.let { r -> return r }
                    }
                } catch (_: Exception) {}
                val path = uri.path ?: return null
                val atIdx = path.indexOf('@')
                if (atIdx >= 0) {
                    val afterAt = path.substring(atIdx + 1)
                    val coordPart = afterAt.split(',').take(2).joinToString(",")
                    parseLatLonString(coordPart)?.let { return it }
                }
                return null
            }
            else -> return null
        }
    }

    private fun parseLatLonString(s: String): Pair<Double, Double>? {
        val cleaned = s.trim().replace(" ", "")
        val parts = cleaned.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0) return null
        if (lon < -180.0 || lon > 180.0) return null
        return lat to lon
    }

    private fun clearOrderTarget() {
        OrderTargetStore.clear(applicationContext)
        val svc = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_ORDER_TARGET_CLEAR)
        }
        try { startService(svc) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { pointBroadcastReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
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
            setAction(OverlayService.ACTION_START)
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
            setAction(OverlayService.ACTION_STOP)
        }
        startService(intent)
    }

    private fun updateOverlayPoints(json: String, activeId: String?) {
        OverlayService.currentPointsJson = json
        if (activeId != null) OverlayService.currentActiveId = activeId
        OverlayPrefs.savePoints(this, json)
        if (activeId != null) OverlayPrefs.saveActiveId(this, activeId)
        val intent = Intent(this, OverlayService::class.java).apply {
            setAction(OverlayService.ACTION_UPDATE_POINTS)
            putExtra(OverlayService.EXTRA_POINTS_JSON, json)
            if (activeId != null) putExtra(OverlayService.EXTRA_ACTIVE_ID, activeId)
        }
        try { startService(intent) } catch (_: Exception) {}
    }
}

const val ACTION_ORDER_TARGET_UPDATED = "com.s1xck.fmagnit.ORDER_TARGET_UPDATED"
const val ACTION_ORDER_START_MOCK = "com.s1xck.fmagnit.ORDER_START_MOCK"