package com.s1xck.fmagnit

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Сервис маршрутизации через BRouter (офлайн).
 *
 * Архитектура:
 * - Карты (.rd5) лежат в: context.getExternalFilesDir("brouter/segments4")
 * - Профили (.brf) лежат в: context.filesDir/brouter/profiles2
 * - Кэш маршрутов: context.cacheDir/route_cache
 *
 * Использование:
 *   val route = RouteService.getInstance(context).buildRoute(fromLat, fromLon, toLat, toLon)
 */
class RouteService private constructor(private val context: Context) {

    data class RouteResult(
        val points: List<Pair<Double, Double>>, // (lat, lon)
        val distanceMeters: Double,
        val timeSeconds: Long,
        val source: String // "brouter" | "cache" | "straight"
    )

    companion object {
        private const val TAG = "RouteService"
        private const val PROFILE_NAME = "courier"
        private const val ROUTE_CACHE_MAX_BYTES = 50L * 1024L * 1024 // 50 МБ
        private const val COORDINATE_PRECISION = 6 // ~10 см

        @Volatile
        private var instance: RouteService? = null

        fun getInstance(context: Context): RouteService {
            return instance ?: synchronized(this) {
                instance ?: RouteService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val segmentsDir: File by lazy {
        File(context.getExternalFilesDir(null), "brouter/segments4").apply { mkdirs() }
    }

    private val profilesDir: File by lazy {
        File(context.filesDir, "brouter/profiles2").apply { mkdirs() }
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, "route_cache").apply { mkdirs() }
    }

    private var isInitialized = false

    /**
     * Инициализация BRouter. Вызывать при старте приложения.
     * Копирует профили из assets в рабочую папку, если их нет.
     */
    fun initialize() {
        if (isInitialized) return
        try {
            copyAssetProfilesIfNeeded()
            isInitialized = true
            Log.i(TAG, "RouteService initialized. Segments: ${segmentsDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RouteService", e)
            CrashLogger.log(context, "RouteService_initialize", e)
        }
    }

    /**
     * Проверяет, есть ли карты для заданных координат.
     * BRouter хранит карты в тайлах 5°×5°, определяемых по имени файла.
     */
    fun hasMapsForLocation(lat: Double, lon: Double): Boolean {
        val tileName = getTileName(lat, lon)
        val expectedFile = File(segmentsDir, "$tileName.rd5")
        return expectedFile.exists()
    }

    /**
     * Возвращает список доступных регионов (имена файлов без расширения).
     */
    fun getAvailableRegions(): List<String> {
        return segmentsDir.listFiles()
            ?.filter { it.extension == "rd5" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * Основной метод: строит маршрут от (fromLat, fromLon) до (toLat, toLon).
     *
     * Приоритет:
     * 1. Кэш (если есть)
     * 2. BRouter (если есть карты)
     * 3. Прямая линия (fallback)
     */
    fun buildRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteResult {
        // 1. Проверяем кэш
        val cached = loadFromCache(fromLat, fromLon, toLat, toLon)
        if (cached != null) {
            Log.d(TAG, "Route loaded from cache")
            return cached.copy(source = "cache")
        }

        // 2. Проверяем наличие карт
        val hasFrom = hasMapsForLocation(fromLat, fromLon)
        val hasTo = hasMapsForLocation(toLat, toLon)

        if (!hasFrom || !hasTo) {
            Log.w(TAG, "No maps for route. hasFrom=$hasFrom, hasTo=$hasTo")
            return buildStraightLine(fromLat, fromLon, toLat, toLon)
        }

        // 3. Строим через BRouter
        return try {
            val route = buildWithBRouter(fromLat, fromLon, toLat, toLon)
            if (route != null) {
                saveToCache(fromLat, fromLon, toLat, toLon, route)
                route.copy(source = "brouter")
            } else {
                buildStraightLine(fromLat, fromLon, toLat, toLon)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BRouter failed, falling back to straight line", e)
            CrashLogger.log(context, "RouteService_buildRoute", e)
            buildStraightLine(fromLat, fromLon, toLat, toLon)
        }
    }

    /**
     * Очищает кэш маршрутов.
     */
    fun clearCache() {
        try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    // ===================== ВНУТРЕННЯЯ ЛОГИКА =====================

    private fun buildWithBRouter(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteResult? {
        // TODO: Реальная интеграция с BRouter API.
        // BRouter имеет класс BRouterService или можно использовать MessageCode.
        //
        // Псевдокод (зависит от точного API библиотеки):
        //
        // val router = BRouterService(context)
        // val params = RoutingParams().apply {
        //     profile = PROFILE_NAME
        //     fromLat = fromLat
        //     fromLon = fromLon
        //     toLat = toLat
        //     toLon = toLon
        // }
        // val result = router.computeRoute(params)
        //
        // val points = result.trackPoints.map { it.latitude to it.longitude }
        // return RouteResult(
        //     points = points,
        //     distanceMeters = result.totalDistance,
        //     timeSeconds = result.totalTime / 1000,
        //     source = "brouter"
        // )

        Log.w(TAG, "BRouter integration not yet implemented. Using straight line.")
        return null
    }

    private fun buildStraightLine(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteResult {
        val distance = haversineDistance(fromLat, fromLon, toLat, toLon)
        // Средняя скорость курьера на скутере: 25 км/ч в городе
        val timeSeconds = (distance / 25000.0 * 3600).toLong()

        return RouteResult(
            points = listOf(fromLat to fromLon, toLat to toLon),
            distanceMeters = distance,
            timeSeconds = timeSeconds,
            source = "straight"
        )
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dPhi / 2).let { it * it } +
                kotlin.math.cos(phi1) * kotlin.math.cos(phi2) *
                kotlin.math.sin(dLambda / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    private fun getTileName(lat: Double, lon: Double): String {
        // BRouter использует тайлы 5°×5°. Имя файла: lon_lat
        // Например, для Москвы (55.75, 37.62): lon=35, lat=55 -> "37.62_55.75"
        // Точная логика: https://github.com/abrensch/brouter/blob/master/brouter-core/src/main/java/btools/router/OsmFileFormats.java
        val lonTile = (lon / 5).toInt() * 5
        val latTile = (lat / 5).toInt() * 5
        return String.format(Locale.US, "%d_%d", lonTile, latTile)
    }

    private fun getCacheKey(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val fl = roundCoord(fromLat)
        val fLn = roundCoord(fromLon)
        val tl = roundCoord(toLat)
        val tLn = roundCoord(toLon)
        return "${fl}_$fLn-$tl_$tLn"
    }

    private fun roundCoord(coord: Double): Long {
        return (coord * 1000000).roundToInt().toLong()
    }

    private fun loadFromCache(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteResult? {
        return try {
            val key = getCacheKey(fromLat, fromLon, toLat, toLon)
            val file = File(cacheDir, key)
            if (!file.exists()) return null

            val lines = file.readLines()
            if (lines.size < 3) return null

            val distance = lines[0].toDouble()
            val time = lines[1].toLong()
            val points = lines.drop(2).map { line ->
                val parts = line.split(",")
                parts[0].toDouble() to parts[1].toDouble()
            }

            RouteResult(points, distance, time, "cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load route from cache", e)
            null
        }
    }

    private fun saveToCache(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        route: RouteResult
    ) {
        try {
            val key = getCacheKey(fromLat, fromLon, toLat, toLon)
            val file = File(cacheDir, key)

            val content = buildString {
                appendLine(route.distanceMeters)
                appendLine(route.timeSeconds)
                route.points.forEach { (lat, lon) ->
                    appendLine("$lat,$lon")
                }
            }

            file.writeText(content)

            // Ограничиваем размер кэша
            trimCacheIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save route to cache", e)
        }
    }

    private fun trimCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var totalSize = files.sumOf { it.length() }

            var i = 0
            while (totalSize > ROUTE_CACHE_MAX_BYTES && i < files.size) {
                totalSize -= files[i].length()
                files[i].delete()
                i++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trim cache", e)
        }
    }

    private fun copyAssetProfilesIfNeeded() {
        try {
            val assets = context.assets.list("brouter/profiles2") ?: return
            for (asset in assets) {
                val targetFile = File(profilesDir, asset)
                if (!targetFile.exists()) {
                    context.assets.open("brouter/profiles2/$asset").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Copied profile: $asset")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy profiles from assets", e)
        }
    }
}
