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

object MockManager {
    private const val TAG = "FMAGNIT_MOCK"
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null
    private var baseLat: Double = 0.0
    private var baseLon: Double = 0.0
    private var baseAcc: Double = 3.0
    private var baseAlt: Double = 0.0
    private var displayLat: Double = 0.0
    private var displayLon: Double = 0.0
    @Volatile private var running: Boolean = false

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
        for (p in providers) {
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
        } catch (_: Exception) {}
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

    private fun applyJitter(offsetMeters: Double = 2.5) {
        val r = Random.nextDouble(-1.0, 1.0) + Random.nextDouble(-1.0, 1.0)
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val dx = r * offsetMeters * cos(angle); val dy = r * offsetMeters * sin(angle)
        val metersPerDegLat = 111_320.0; val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))
        displayLat = baseLat + dy / metersPerDegLat; displayLon = baseLon + dx / metersPerDegLon
    }

    private fun pushLocation() {
        val lm = locationManager ?: return; if (!running) return
        applyJitter()
        val jitterAcc = baseAcc + Random.nextDouble(-3.0, 3.0); val acc = jitterAcc.coerceAtLeast(1.0)
        val speed = Random.nextFloat() * 0.4f; val bearing = Random.nextFloat() * 360f
        try { for (p in providers) { val loc = buildLocation(p, displayLat, displayLon, acc, baseAlt, speed, bearing); try { lm.setTestProviderLocation(p, loc) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    fun start(ctx: Context, lat: Double, lon: Double, acc: Double = 3.0, alt: Double = 0.0, intervalMs: Long = 1000L): Boolean {
        ensureInit(ctx)
        if (!isMockLocationEnabled(ctx)) return false
        return try {
            baseLat = lat; baseLon = lon; baseAcc = acc; baseAlt = alt; displayLat = lat; displayLon = lon
            for (p in providers) ensureProvider(p)
            running = true; pushLocation(); startPushLoop(intervalMs); true
        } catch (e: Exception) { false }
    }

    private fun startPushLoop(baseIntervalMs: Long) {
        stopPushLoop()
        val r = object : Runnable { override fun run() { pushLocation(); val jitter = baseIntervalMs * (0.7 + Random.nextDouble() * 0.6); handler?.postDelayed(this, jitter.toLong()) } }
        pushRunnable = r; handler?.postDelayed(r, baseIntervalMs)
    }

    private fun stopPushLoop() { pushRunnable?.let { handler?.removeCallbacks(it) }; pushRunnable = null }

    fun update(ctx: Context, lat: Double, lon: Double, acc: Double = 3.0, alt: Double = 0.0): Boolean {
        ensureInit(ctx)
        return try { baseLat = lat; baseLon = lon; baseAcc = acc; baseAlt = alt; displayLat = lat; displayLon = lon; pushLocation(); true } catch (_: Exception) { false }
    }

    fun stop(ctx: Context): Boolean {
        ensureInit(ctx); running = false; stopPushLoop()
        return try {
            Thread.sleep(50)
            for (p in providers) { try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}; try { locationManager?.removeTestProvider(p) } catch (_: Exception) {} }
            Thread.sleep(150)
            for (p in providers) { try { locationManager?.removeTestProvider(p) } catch (_: Exception) {} }
            true
        } catch (_: Exception) { false }
    }

    fun forceReset(ctx: Context): Boolean {
        ensureInit(ctx); running = false; stopPushLoop()
        return try {
            for (p in providers) { try { locationManager?.setTestProviderEnabled(p, false) } catch (_: Exception) {}; try { locationManager?.removeTestProvider(p) } catch (_: Exception) {} }
            Thread.sleep(200)
            for (p in providers) { try { locationManager?.removeTestProvider(p) } catch (_: Exception) {} }
            true
        } catch (_: Exception) { false }
    }
}