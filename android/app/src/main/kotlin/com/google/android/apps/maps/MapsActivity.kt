package com.google.android.apps.maps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.util.Log
import android.widget.Toast
import com.s1xck.fmagnit.ACTION_POINT_START
import com.s1xck.fmagnit.ACTION_POINT_STOP
import com.s1xck.fmagnit.CrashLogger
import com.s1xck.fmagnit.ForegroundDetector
import com.s1xck.fmagnit.MainAccessibilityService
import com.s1xck.fmagnit.OrderTargetStore
import com.s1xck.fmagnit.OverlayBridge
import com.s1xck.fmagnit.OverlayPrefs
import com.s1xck.fmagnit.OverlayService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MapsActivity : FlutterActivity() {

    companion object {
        private const val TAG = "FMAGNIT_INTENT"
        private const val NOTIF_CHANNEL = "fmagnit_intent_debug"
        private const val NOTIF_ID = 7331
    }

    private val mockChannelName = "com.s1xck.fmagnit/mock_location"
    private val overlayChannelName = "com.s1xck.fmagnit/overlay"

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null

    private var baseLat: Double = 0.0
    private var baseLon: Double = 0.0
    private var baseAcc: Double = 3.0
    private var baseAlt: Double = 0.0

    private var displayLat: Double = 0.0
    private var displayLon: Double = 0.0

    private var isMockRunning = false

    private var pointBroadcastReceiver: BroadcastReceiver? = null
    private var overlayChannel: MethodChannel? = null
    private var mockChannel: MethodChannel? = null

    // Защита от повторной обработки одного и того же URI
    private var lastHandledUri: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        try {
            super.configureFlutterEngine(flutterEngine)
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "configureFlutterEngine", e)
            throw e
        }

        try {
            locationManager =
                applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            handler = Handler(Looper.getMainLooper())

            mockChannel = MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                mockChannelName
            )
            mockChannel?.setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "isMockLocationEnabled" -> result.success(isMockLocationEnabled())
                        "openDeveloperSettings" -> { openDeveloperSettings(); result.success(null) }
                        "isMockRunning" -> result.success(isMockRunning)
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
                        "forceResetMock" -> result.success(forceResetMock())
                        "getRealLocation" -> {
                            val loc = getBestRealLocation()
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
                        "getLastOrderTarget" -> {
                            result.success(OrderTargetStore.loadLast(this))
                        }
                        "getLastCrash" -> {
                            result.success(CrashLogger.load(this))
                        }
                        "clearCrash" -> {
                            CrashLogger.clear(this)
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
                        "clearOrderTarget" -> {
                            clearOrderTarget()
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
                    try {
                        when (intent?.action) {
                            ACTION_POINT_START -> {
                                val id = intent.getStringExtra("id")
                                overlayChannel?.invokeMethod("onOverlayStart", mapOf("id" to id))
                            }
                            ACTION_POINT_STOP -> {
                                forceResetMock()
                                overlayChannel?.invokeMethod("onOverlayStop", null)
                            }
                        }
                    } catch (e: Throwable) {
                        CrashLogger.log(applicationContext, "broadcast_receiver", e)
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
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "configureFlutterEngine_body", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Не обрабатываем тут — сделаем это в onResume
    }

    override fun onResume() {
        super.onResume()

        // Логируем каждый onResume с текущим интентом
        try {
            val currentIntent = intent
            val data: Uri? = currentIntent?.data
            CrashLogger.logMessage(
                applicationContext,
                "onResume",
                "action=${currentIntent?.action}\n" +
                        "data=$data\n" +
                        "scheme=${data?.scheme}\n" +
                        "ssp=${data?.schemeSpecificPart}"
            )
        } catch (_: Exception) {}

        // Обрабатываем интент с задержкой — даём Flutter движку подняться
        handler?.postDelayed({
            handleIncomingIntent(intent)
        }, 500)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            val action = intent?.action
            val data: Uri? = intent?.data

            Log.d(TAG, "===== Incoming intent =====")
            Log.d(TAG, "action=$action")
            Log.d(TAG, "data=$data")

            CrashLogger.logMessage(
                applicationContext,
                "intent_received",
                "action=$action\n" +
                        "data=$data\n" +
                        "scheme=${data?.scheme}\n" +
                        "ssp=${data?.schemeSpecificPart}\n" +
                        "host=${data?.host}\n" +
                        "path=${data?.path}\n" +
                        "query=${data?.query}\n" +
                        "isOpaque=${data?.isOpaque}\n" +
                        "isHierarchical=${data?.isHierarchical}"
            )

            if (data == null) {
                Log.d(TAG, "data == null, skipping")
                return
            }

            // Защита от повторной обработки того же URI
            val uriStr = data.toString()
            if (uriStr == lastHandledUri) {
                Log.d(TAG, "Same URI already handled — skipping")
                return
            }

            val parsed = parseCoordinates(data)
            if (parsed == null) {
                Log.w(TAG, "Failed to parse coordinates from $data")
                CrashLogger.logMessage(
                    applicationContext,
                    "parse_failed",
                    "Could not parse: $data\n" +
                            "scheme=${data.scheme}\n" +
                            "ssp=${data.schemeSpecificPart}\n" +
                            "host=${data.host}\n" +
                            "path=${data.path}\n" +
                            "query=${data.query}"
                )
                showToast("fmagnit: не удалось распарсить координаты")
                showNotification(
                    "fmagnit: координаты не распознаны",
                    data.toString()
                )
                lastHandledUri = uriStr
                finish()
                return
            }

            val (lat, lon) = parsed
            Log.d(TAG, "Parsed coordinates: lat=$lat, lon=$lon")

            lastHandledUri = uriStr

            CrashLogger.logMessage(
                applicationContext,
                "parse_ok",
                "data=$data\nscheme=${data.scheme}\nlat=$lat lon=$lon"
            )

            OrderTargetStore.save(applicationContext, lat, lon, data.toString())

            showToast("fmagnit получил: %.6f, %.6f".format(lat, lon))
            showNotification(
                "fmagnit получил заказ",
                "%.6f, %.6f".format(lat, lon)
            )

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

            handler?.postDelayed({ finish() }, 800)
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "handleIncomingIntent_CRASH", e)
            Log.e(TAG, "CRASH in handleIncomingIntent", e)
        }
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    private fun showNotification(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                    val ch = NotificationChannel(
                        NOTIF_CHANNEL, "fmagnit intent debug",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                    nm.createNotificationChannel(ch)
                }
            }

            val openIntent = Intent(this, MapsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

            val builder: android.app.Notification.Builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.app.Notification.Builder(this, NOTIF_CHANNEL)
                } else {
                    @Suppress("DEPRECATION")
                    android.app.Notification.Builder(this)
                }

            val notif = builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    private fun parseCoordinates(uri: Uri): Pair<Double, Double>? {
        val scheme = uri.scheme ?: return null

        when (scheme.lowercase()) {
            "google.navigation" -> {
                val ssp = uri.schemeSpecificPart ?: return null
                // Формат "q=56.8558,53.2218" или "q=56.8558,53.2218&mode=d"
                // или просто "56.8558,53.2218"
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
                    uri.getQueryParameter("ll")?.let {
                        parseLatLonString(it)?.let { r -> return r }
                    }
                    uri.getQueryParameter("daddr")?.let {
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

    private fun getBestRealLocation(): Location? {
        val lm = locationManager ?: return null
        var best: Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null) {
                best = loc
                continue
            }
            val ageDiff = best.time - loc.time
            if (ageDiff > 10_000) {
                best = loc
                continue
            }
            if (loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        return best
    }

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

    private fun applyJitter(offsetMeters: Double = 2.5) {
        val r = Random.nextDouble(-1.0, 1.0) + Random.nextDouble(-1.0, 1.0)
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val dx = r * offsetMeters * cos(angle)
        val dy = r * offsetMeters * sin(angle)

        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

        displayLat = baseLat + dy / metersPerDegLat
        displayLon = baseLon + dx / metersPerDegLon
    }

    private fun pushLocation() {
        val lm = locationManager ?: return
        if (!isMockRunning) return

        applyJitter()

        val jitterAcc = baseAcc + Random.nextDouble(-3.0, 3.0)
        val acc = jitterAcc.coerceAtLeast(1.0)

        val speed = Random.nextFloat() * 0.4f
        val bearing = Random.nextFloat() * 360f

        try {
            for (p in providers) {
                val loc = buildLocation(p, displayLat, displayLon, acc, baseAlt, speed, bearing)
                try { lm.setTestProviderLocation(p, loc) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

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
            Thread.sleep(50)
            for (p in providers) {
                try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}
                try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
            }
            Thread.sleep(150)
            for (p in providers) {
                try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) { false }
    }

    private fun forceResetMock(): Boolean {
        return try {
            isMockRunning = false
            stopPushLoop()

            for (p in providers) {
                try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}
                try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
            }

            Thread.sleep(200)

            for (p in providers) {
                try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
            }

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