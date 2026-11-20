package com.s1xck.fmagnit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.directions.bicycle.BicycleOptions
import com.yandex.mapkit.directions.bicycle.BicycleRouterFactory
import com.yandex.mapkit.directions.bicycle.BicycleSession
import com.yandex.mapkit.directions.bicycle.BicycleSessionListener
import com.yandex.mapkit.directions.bicycle.BicycleRoute
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRouterFactory
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.DrivingSessionListener
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.directions.electric_bike.ElectricBikeOptions
import com.yandex.mapkit.directions.electric_bike.ElectricBikeRouterFactory
import com.yandex.mapkit.directions.electric_bike.ElectricBikeSession
import com.yandex.mapkit.directions.electric_bike.ElectricBikeSessionListener
import com.yandex.mapkit.directions.electric_bike.ElectricBikeRoute
import com.yandex.mapkit.directions.pedestrian.PedestrianOptions
import com.yandex.mapkit.directions.pedestrian.PedestrianRouterFactory
import com.yandex.mapkit.directions.pedestrian.PedestrianSession
import com.yandex.mapkit.directions.pedestrian.PedestrianSessionListener
import com.yandex.mapkit.directions.pedestrian.PedestrianRoute
import com.yandex.mapkit.traffic.TrafficLayerFactory
import com.yandex.runtime.Error
import com.yandex.runtime.image.ImageProvider

/**
 * Единая точка входа в MapKit:
 *  - setApiKey ДО initialize, ровно один раз на процесс;
 *  - ключ из BuildConfig (секрет GitHub), пусто -> fallback на OSM;
 *  - lifecycle onStart/onStop у фабрики и у MapView;
 *  - night mode, камера, placemarks, пробки, маршруты 4 типов.
 */
object MapKitHub {

    @Volatile private var initialized = false
    @Volatile private var started = false
    private val lock = Any()

    data class RouteResult(
        val points: List<Pair<Double, Double>>,
        val eta: String,
        val distance: String
    )

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
            try {
                val layer = TrafficLayerFactory.createTrafficLayer(mv.mapWindow)
                mv.map.addTrafficLayer(layer)
            } catch (e: Throwable) {
                CrashLogger.log(ctx, "mapkit_traffic", e)
            }
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

    fun drawRoute(map: Map, points: List<Pair<Double, Double>>) {
        try {
            if (points.size < 2) return
            val pl = Polyline(points.map { Point(it.first, it.second) })
            val obj = map.mapObjects.addPolyline(pl)
            obj.setStrokeColor(Color.argb(230, 227, 6, 19))
            obj.setStrokeWidth(4f)
        } catch (e: Throwable) {
            CrashLogger.log(null, "mapkit_polyline", e)
        }
    }

    /**
     * Маршрут через MapKit. mode: auto / bicycle / electric_bike / pedestrian.
     * Колбэк вызывается в произвольном потоке — вызывающий сам постит в main.
     */
    fun requestRoute(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: String,
        onResult: (RouteResult?) -> Unit
    ) {
        if (!ensureInit(ctx)) {
            onResult(null)
            return
        }
        val points = listOf(
            RequestPoint(Point(fromLat, fromLon), RequestPointType.WAYPOINT, null),
            RequestPoint(Point(toLat, toLon), RequestPointType.WAYPOINT, null)
        )
        try {
            when (mode) {
                "bicycle" -> {
                    val router = BicycleRouterFactory.createBicycleRouter()
                    router.requestRoutes(points, BicycleOptions(), object : BicycleSessionListener {
                        override fun onBicycleRoutesResponse(routes: List<BicycleRoute>) {
                            onResult(extract(routes.firstOrNull()?.geometry, routes.firstOrNull()?.metadata?.weight?.time?.text, routes.firstOrNull()?.metadata?.weight?.distance?.text))
                        }
                        override fun onBicycleRoutesError(error: Error) {
                            CrashLogger.logMessage(ctx, "route_error", error.toString())
                            onResult(null)
                        }
                    })
                }
                "electric_bike" -> {
                    val router = ElectricBikeRouterFactory.createElectricBikeRouter()
                    router.requestRoutes(points, ElectricBikeOptions(), object : ElectricBikeSessionListener {
                        override fun onElectricBikeRoutesResponse(routes: List<ElectricBikeRoute>) {
                            onResult(extract(routes.firstOrNull()?.geometry, routes.firstOrNull()?.metadata?.weight?.time?.text, routes.firstOrNull()?.metadata?.weight?.distance?.text))
                        }
                        override fun onElectricBikeRoutesError(error: Error) {
                            CrashLogger.logMessage(ctx, "route_error", error.toString())
                            onResult(null)
                        }
                    })
                }
                "pedestrian" -> {
                    val router = PedestrianRouterFactory.createPedestrianRouter()
                    router.requestRoutes(points, PedestrianOptions(), object : PedestrianSessionListener {
                        override fun onPedestrianRoutesResponse(routes: List<PedestrianRoute>) {
                            onResult(extract(routes.firstOrNull()?.geometry, routes.firstOrNull()?.metadata?.weight?.time?.text, routes.firstOrNull()?.metadata?.weight?.distance?.text))
                        }
                        override fun onPedestrianRoutesError(error: Error) {
                            CrashLogger.logMessage(ctx, "route_error", error.toString())
                            onResult(null)
                        }
                    })
                }
                else -> {
                    val router = DrivingRouterFactory.createDrivingRouter()
                    router.requestRoutes(points, DrivingOptions(), VehicleOptions(), object : DrivingSessionListener {
                        override fun onDrivingRoutesResponse(routes: List<DrivingRoute>) {
                            onResult(extract(routes.firstOrNull()?.geometry, routes.firstOrNull()?.metadata?.weight?.time?.text, routes.firstOrNull()?.metadata?.weight?.distance?.text))
                        }
                        override fun onDrivingRoutesError(error: Error) {
                            CrashLogger.logMessage(ctx, "route_error", error.toString())
                            onResult(null)
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            CrashLogger.log(ctx, "route_request", e)
            onResult(null)
        }
    }

    private fun extract(
        geometry: Polyline?,
        eta: String?,
        distance: String?
    ): RouteResult? {
        if (geometry == null) return null
        val pts = geometry.points.map { it.latitude to it.longitude }
        if (pts.size < 2) return null
        return RouteResult(pts, eta ?: "", distance ?: "")
    }
}