package com.s1xck.fmagnit

import android.content.Context
import android.graphics.Bitmap
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider

/**
 * Единая точка входа в MapKit:
 *  - setApiKey ДО initialize, ровно один раз на процесс;
 *  - ключ из BuildConfig (секрет GitHub), пусто -> fallback на OSM;
 *  - lifecycle onStart/onStop у фабрики и у MapView;
 *  - night mode, камера, placemarks с bitmap-иконками.
 */
object MapKitHub {

    @Volatile private var initialized = false
    @Volatile private var started = false
    private val lock = Any()

    fun hasKey(): Boolean = try {
        BuildConfig.MAPKIT_API_KEY.isNotBlank()
    } catch (_: Throwable) { false }

    fun ensureInit(ctx: Context): Boolean {
        if (initialized) return true
        if (!hasKey()) return false
        synchronized(lock) {
            if (initialized) return true
            return try {
                MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
                MapKitFactory.initialize(ctx.applicationContext)
                initialized = true
                true
            } catch (e: Throwable) {
                CrashLogger.log(ctx, "mapkit_init", e)
                false
            }
        }
    }

    fun createMapView(ctx: Context, night: Boolean): MapView? {
        if (!ensureInit(ctx)) return null
        return try {
            val mv = MapView(ctx)
            mv.map.isNightModeEnabled = night
            mv
        } catch (e: Throwable) {
            CrashLogger.log(ctx, "mapkit_view", e)
            null
        }
    }

    fun start() {
        if (initialized && !started) {
            try {
                MapKitFactory.getInstance().onStart()
                started = true
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        if (initialized && started) {
            try {
                MapKitFactory.getInstance().onStop()
                started = false
            } catch (_: Throwable) {}
        }
    }

    fun camera(map: Map, lat: Double, lon: Double, zoom: Float, azimuth: Float = 0f) {
        try {
            map.move(CameraPosition(Point(lat, lon), zoom, azimuth, 0f))
        } catch (_: Throwable) {}
    }

    fun zoomBy(map: Map, delta: Float) {
        try {
            val cp = map.cameraPosition
            val z = (cp.zoom + delta).coerceIn(1f, 20f)
            map.move(CameraPosition(cp.target, z, cp.azimuth, cp.tilt))
        } catch (_: Throwable) {}
    }

    fun resetNorth(map: Map) {
        try {
            val cp = map.cameraPosition
            map.move(CameraPosition(cp.target, cp.zoom, 0f, cp.tilt))
        } catch (_: Throwable) {}
    }

    fun azimuth(map: Map): Float = try {
        map.cameraPosition.azimuth
    } catch (_: Throwable) { 0f }

    fun placemark(map: Map, lat: Double, lon: Double, icon: Bitmap? = null) {
        try {
            val pm = map.mapObjects.addPlacemark()
            pm.geometry = Point(lat, lon)
            if (icon != null) pm.setIcon(ImageProvider.fromBitmap(icon))
        } catch (_: Throwable) {}
    }
}