package com.s1xck.fmagnit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.traffic.TrafficLayer
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.FitnessOptions
import com.yandex.mapkit.transport.masstransit.Route as MtRoute
import com.yandex.mapkit.transport.masstransit.RouteOptions
import com.yandex.mapkit.transport.masstransit.Session as MtSession
import com.yandex.mapkit.transport.masstransit.TimeOptions
import com.yandex.runtime.Error
import com.yandex.runtime.image.ImageProvider
import java.lang.reflect.Proxy

/**
 * Единая точка входа в MapKit:
 *  - setApiKey ДО initialize, ровно один раз на процесс;
 *  - ключ из BuildConfig (секрет GitHub), пусто -> fallback на OSM;
 *  - lifecycle onStart/onStop у фабрики и у MapView;
 *  - night mode, камера, placemarks, пробки (по умолчанию скрыты), маршруты 4 типов.
 *
 * Имена API сверены с исходниками официального плагина yandex_mapkit
 * и с фактическим компилятором против 4.13:
 *  - пробки: MapKitFactory.createTrafficLayer + setTrafficVisible;
 *  - авто: DirectionsFactory.createDrivingRouter(COMBINED), вложенный листенер;
 *  - вело/пешком: TransportFactory.createBicycleRouterV2 / createPedestrianRouter;
 *  - RequestPoint: конструкция через рефлексию (ctor 2 -> 5 -> 0 + поля по типам);
 *  - электровел: рефлексия по createElectricBikeRouter[V2], иначе честный вело-fallback.
 */
object MapKitHub {

    @Volatile private var initialized = false
    @Volatile private var started = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var trafficLayer: TrafficLayer? = null
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
        appCtx = ctx.applicationContext
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
                val layer = MapKitFactory.getInstance().createTrafficLayer(mv.mapWindow)
                layer.setTrafficVisible(OverlayPrefs.loadTrafficEnabled(ctx))
                trafficLayer = layer
            } catch (e: Throwable) {
                CrashLogger.log(ctx, "mapkit_traffic", e)
            }
            mv
        } catch (e: Throwable) {
            CrashLogger.log(ctx, "mapkit_view", e)
            null
        }
    }

    fun setTrafficVisible(visible: Boolean) {
        try {
            trafficLayer?.setTrafficVisible(visible)
        } catch (_: Throwable) {}
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

    fun placemark(map: Map, lat: Double, lon: Double, icon: Bitmap? = null): PlacemarkMapObject? {
        return try {
            val pm = map.mapObjects.addPlacemark()
            pm.geometry = Point(lat, lon)
            if (icon != null) pm.setIcon(ImageProvider.fromBitmap(icon))
            pm
        } catch (_: Throwable) {
            null
        }
    }

    fun drawRoute(map: Map, points: List<Pair<Double, Double>>) {
        try {
            if (points.size < 2) return
            val pl = Polyline(points.map { Point(it.first, it.second) })
            val obj = map.mapObjects.addPolyline(pl)
            obj.setStrokeColor(Color.argb(230, 227, 6, 19))
            obj.setStrokeWidth(4f)
        } catch (e: Throwable) {
            val c = appCtx
            if (c != null) CrashLogger.logMessage(c, "mapkit_polyline", e.toString())
        }
    }

    /**
     * RequestPoint в разных версиях SDK имеет разные конструкторы
     * (2 аргумента / 5 аргументов / пустой + поля). Не угадываем —
     * выбираем фактический конструктор рефлексией, иначе пустой + поля по типам.
     */
    private fun requestPoint(lat: Double, lon: Double): RequestPoint {
        val p = Point(lat, lon)
        val clazz = RequestPoint::class.java
        val ctors = clazz.constructors

        ctors.firstOrNull { it.parameterTypes.size == 2 }?.let { c ->
            return c.newInstance(p, RequestPointType.WAYPOINT) as RequestPoint
        }
        ctors.firstOrNull { it.parameterTypes.size == 5 }?.let { c ->
            return c.newInstance(p, RequestPointType.WAYPOINT, null, null, null) as RequestPoint
        }

        val rp = clazz.newInstance()
        for (f in clazz.fields) {
            try {
                f.isAccessible = true
                if (f.type == Point::class.java) {
                    f.set(rp, p)
                } else if (f.type == RequestPointType::class.java) {
                    f.set(rp, RequestPointType.WAYPOINT)
                }
            } catch (_: Throwable) {}
        }
        return rp
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
            requestPoint(fromLat, fromLon),
            requestPoint(toLat, toLon)
        )
        try {
            when (mode) {
                "bicycle" -> requestBicycle(ctx, points, onResult)
                "pedestrian" -> requestPedestrian(ctx, points, onResult)
                "electric_bike" -> {
                    val ok = requestElectric(ctx, points, onResult)
                    if (!ok) {
                        Toast.makeText(
                            ctx,
                            "Электровел-профиль недоступен в этой версии SDK. Построил вело-маршрут.",
                            Toast.LENGTH_LONG
                        ).show()
                        requestBicycle(ctx, points, onResult)
                    }
                }
                else -> requestDriving(ctx, points, onResult)
            }
        } catch (e: Throwable) {
            CrashLogger.log(ctx, "route_request", e)
            onResult(null)
        }
    }

    private fun requestDriving(ctx: Context, points: List<RequestPoint>, onResult: (RouteResult?) -> Unit) {
        val router = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
        router.requestRoutes(points, DrivingOptions(), VehicleOptions(), object : DrivingSession.DrivingRouteListener {
            override fun onDrivingRoutes(routes: List<DrivingRoute>) {
                val r = routes.firstOrNull()
                if (r == null) {
                    onResult(null)
                    return
                }
                val w = r.metadata?.weight
                val eta = w?.timeWithTraffic?.text ?: w?.time?.text ?: ""
                val dist = w?.distance?.text ?: ""
                onResult(extract(r.geometry, eta, dist))
            }

            override fun onDrivingRoutesError(error: Error) {
                CrashLogger.logMessage(ctx, "route_error", error.toString())
                onResult(null)
            }
        })
    }

    private fun mtListener(ctx: Context, onResult: (RouteResult?) -> Unit): MtSession.RouteListener {
        return object : MtSession.RouteListener {
            override fun onMasstransitRoutes(routes: List<MtRoute>) {
                val r = routes.firstOrNull()
                if (r == null) {
                    onResult(null)
                    return
                }
                val w = r.metadata?.weight
                val eta = w?.time?.text ?: ""
                val dist = w?.walkingDistance?.text ?: ""
                onResult(extract(r.geometry, eta, dist))
            }

            override fun onMasstransitRoutesError(error: Error) {
                CrashLogger.logMessage(ctx, "route_error", error.toString())
                onResult(null)
            }
        }
    }

    private fun requestBicycle(ctx: Context, points: List<RequestPoint>, onResult: (RouteResult?) -> Unit) {
        val router = TransportFactory.getInstance().createBicycleRouterV2()
        router.requestRoutes(
            points,
            TimeOptions(null, null),
            RouteOptions(FitnessOptions(false, false)),
            mtListener(ctx, onResult)
        )
    }

    private fun requestPedestrian(ctx: Context, points: List<RequestPoint>, onResult: (RouteResult?) -> Unit) {
        val router = TransportFactory.getInstance().createPedestrianRouter()
        router.requestRoutes(
            points,
            TimeOptions(null, null),
            RouteOptions(FitnessOptions(false, false)),
            mtListener(ctx, onResult)
        )
    }

    /** Электровел: имя фабрики не документировано -> рефлексия, без выдуманных имён. */
    private fun requestElectric(ctx: Context, points: List<RequestPoint>, onResult: (RouteResult?) -> Unit): Boolean {
        return try {
            val tf = TransportFactory.getInstance()
            val factoryMethod = tf.javaClass.methods.firstOrNull {
                it.name == "createElectricBikeRouterV2" || it.name == "createElectricBikeRouter"
            } ?: return false
            val router = factoryMethod.invoke(tf) ?: return false
            val req = router.javaClass.methods.firstOrNull {
                it.name == "requestRoutes" && it.parameterTypes.size == 4
            } ?: return false

            val listener = Proxy.newProxyInstance(
                ctx.classLoader,
                arrayOf(MtSession.RouteListener::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "onMasstransitRoutes" -> {
                        val list = args?.getOrNull(0) as? List<*>
                        val r = list?.firstOrNull() as? MtRoute
                        if (r == null) {
                            onResult(null)
                        } else {
                            val w = r.metadata?.weight
                            onResult(extract(r.geometry, w?.time?.text ?: "", w?.walkingDistance?.text ?: ""))
                        }
                        null
                    }
                    "onMasstransitRoutesError" -> {
                        CrashLogger.logMessage(ctx, "route_error", args?.getOrNull(0)?.toString() ?: "")
                        onResult(null)
                        null
                    }
                    else -> null
                }
            }

            req.invoke(router, points, TimeOptions(null, null), RouteOptions(FitnessOptions(false, false)), listener)
            true
        } catch (e: Throwable) {
            CrashLogger.log(ctx, "route_electric", e)
            false
        }
    }

    private fun extract(geometry: Polyline?, eta: String, distance: String): RouteResult? {
        if (geometry == null) return null
        val pts = geometry.points.map { it.latitude to it.longitude }
        if (pts.size < 2) return null
        return RouteResult(pts, eta, distance)
    }
}