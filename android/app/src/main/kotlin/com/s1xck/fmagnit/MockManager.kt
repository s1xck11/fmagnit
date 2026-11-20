package com.s1xck.fmagnit

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Агрессивная подмена:
 *  - провайдеры gps + network + fused (fused пропускается, если система не даёт);
 *  - пуш ~350 мс + залп при старте;
 *  - мелкий джиттер и плавный bearing — стабильная «живая» позиция.
 *
 * Предел: isFromMockProvider() система ставит сама на test-provider локации,
 * скрыть флаг без root/Xposed невозможно.
 */
object MockManager {
    private const val TAG = "FMAGNIT_MOCK"
    private const val PUSH_INTERVAL_MS = 350L
    private const val JITTER_METERS = 1.2

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused"
    )
    private val activeProviders = ArrayList<String>()

    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null
    private var baseLat: Double = 0.0
    private var baseLon: Double = 0.0
    private var baseAcc: Double = 3.0
    private var baseAlt: Double = 0.0
    private var displayLat: Double = 0.0
    private var displayLon: Double = 0.0
    private var lastBearing: Float = 0f
    @Volatile private var running: Boolean = false

    private val lock = Any()
    @Volatile private var generation: Int = 0

    private fun ensureInit(ctx: Context) {
        if (locationManager == null) locationManager = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (handler == null) handler = Handler(Looper.getMainLooper())
    }

    fun isRunning(): Boolean = running

    fun isMockLocationEnabled(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val appOps = ctx.applicationContext.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), ctx.packageName)
                } else {
                    @Suppress("DEPRECATION") appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), ctx.packageName)
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else {
                @Suppress("DEPRECATION") android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ALLOW_MOCK_LOCATION) == "1"
            }
        } catch (_: Exception) { false }
    }

    fun getRealLocation(ctx: Context): Location? {
        ensureInit(ctx)
        val lm = locationManager ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null) { best = loc; continue }
            val ageDiff = best.time - loc.time
            if (ageDiff > 10_000) { best = loc; continue }
            if (loc.accuracy < best.accuracy) best = loc
        }
        return best
    }

    fun openDeveloperSettings(ctx: Context) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun ensureProvider(providerName: String) {
        val lm = locationManager ?: return
        try { lm.removeTestProvider(providerName) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lm.addTestProvider(providerName, false, false, false, false, true, true, true, android.location.provider.ProviderProperties.POWER_USAGE_LOW, android.location.provider.ProviderProperties.ACCURACY_FINE)
            } else {
                @Suppress("DEPRECATION") lm.addTestProvider(providerName, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            }
            lm.setTestProviderEnabled(providerName, true)
            if (!activeProviders.contains(providerName)) activeProviders.add(providerName)
        } catch (_: Exception) {
            // fused может быть закрыт системой — не страшно, gps+network работают
        }
    }

    private fun buildLocation(providerName: String, lat: Double, lon: Double, acc: Double, alt: Double, speed: Float, bearing: Float): Location {
        val loc = Location(providerName)
        loc.latitude = lat; loc.longitude = lon; loc.accuracy = acc.toFloat(); loc.altitude = alt
        loc.time = System.currentTimeMillis(); loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.verticalAccuracyMeters = acc.toFloat(); loc.speedAccuracyMetersPerSecond = (acc * 0.4).toFloat(); loc.bearingAccuracyDegrees = 20f
        }
        loc.speed = speed; loc.bearing = bearing
        return loc
    }

    private fun applyJitter() {
        val r = Random.nextDouble(-1.0, 1.0) + Random.nextDouble(-1.0, 1.0)
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val dx = r * JITTER_METERS * cos(angle); val dy = r * JITTER_METERS * sin(angle)
        val metersPerDegLat = 111_320.0; val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))
        displayLat = baseLat + dy / metersPerDegLat; displayLon = baseLon + dx / metersPerDegLon
    }

    private fun pushLocation() {
        val lm = locationManager ?: return; if (!running) return
        applyJitter()
        val acc = (baseAcc + Random.nextDouble(-1.5, 1.5)).coerceIn(2.0, 8.0)
        val speed = Random.nextFloat() * 0.3f
        lastBearing = ((lastBearing + Random.nextFloat() * 50f - 25f) + 360f) % 360f
        val targets = if (activeProviders.isEmpty()) providers else activeProviders
        try {
            for (p in targets) {
                val loc = buildLocation(p, displayLat, displayLon, acc, baseAlt, speed, lastBearing)
                try { lm.setTestProviderLocation(p, loc) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun start(ctx: Context, lat: Double, lon: Double, acc: Double = 3.0, alt: Double = 0.0, intervalMs: Long = PUSH_INTERVAL_MS): Boolean {
        ensureInit(ctx)
        if (!isMockLocationEnabled(ctx)) return false
        return try {
            synchronized(lock) {
                generation++
                baseLat = lat; baseLon = lon; baseAcc = acc; baseAlt = alt; displayLat = lat; displayLon = lon
                activeProviders.clear()
                for (p in providers) ensureProvider(p)
                running = true
            }
            pushLocation()
            handler?.postDelayed({ pushLocation() }, 120)
            handler?.postDelayed({ pushLocation() }, 260)
            startPushLoop(intervalMs)
            true
        } catch (e: Exception) { false }
    }

    private fun startPushLoop(baseIntervalMs: Long) {
        stopPushLoop()
        val r = object : Runnable {
            override fun run() {
                pushLocation()
                val jitter = baseIntervalMs * (0.85 + Random.nextDouble() * 0.3)
                handler?.postDelayed(this, jitter.toLong())
            }
        }
        pushRunnable = r; handler?.postDelayed(r, baseIntervalMs)
    }

    private fun stopPushLoop() { pushRunnable?.let { handler?.removeCallbacks(it) }; pushRunnable = null }

    fun update(ctx: Context, lat: Double, lon: Double, acc: Double = 3.0, alt: Double = 0.0): Boolean {
        ensureInit(ctx)
        return try { baseLat = lat; baseLon = lon; baseAcc = acc; baseAlt = alt; displayLat = lat; displayLon = lon; pushLocation(); true } catch (_: Exception) { false }
    }

    fun stop(ctx: Context): Boolean {
        ensureInit(ctx)
        running = false
        stopPushLoop()
        teardownAsync(generation)
        return true
    }

    fun forceReset(ctx: Context): Boolean {
        ensureInit(ctx)
        running = false
        stopPushLoop()
        teardownAsync(generation)
        return true
    }

    private fun teardownAsync(gen: Int) {
        val t = Thread {
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
            synchronized(lock) {
                if (gen == generation && !running) {
                    for (p in providers) {
                        try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}
                        try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
                    }
                    activeProviders.clear()
                }
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            synchronized(lock) {
                if (gen == generation && !running) {
                    for (p in providers) {
                        try { locationManager?.removeTestProvider(p) } catch (_: Exception) {}
                    }
                }
            }
        }
        t.name = "fmagnit-teardown"
        t.start()
    }
}