package com.s1xck.fmagnit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object RouteService {
    private const val BROUTER_PACKAGE = "btools.routingapp"
    private const val BROUTER_HTTP_URL = "http://127.0.0.1:17777/brouter"

    /** Проверяет, установлен ли BRouter */
    fun isBRouterInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(BROUTER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Возвращает Intent для открытия BRouter в Play Store или запуска приложения */
    fun getBRouterIntent(context: Context): Intent {
        return if (isBRouterInstalled(context)) {
            context.packageManager.getLaunchIntentForPackage(BROUTER_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BROUTER_PACKAGE"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BROUTER_PACKAGE"))
        }
    }

    /** Делает HTTP-запрос к локальному серверу BRouter */
    fun fetchRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double): RouteResult? {
        return try {
            // profile=trekking отлично подходит для скутеров/велосипедов (избегает магистралей)
            val urlStr = "$BROUTER_HTTP_URL?lonlats=$startLon,$startLat|$endLon,$endLat&profile=trekking&format=geojson"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000 // 3 секунды, чтобы не висло, если сервер не отвечает
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                parseGeoJson(response)
            } else {
                null
            }
        } catch (e: Exception) {
            // Сервер не отвечает или нет соединения
            null
        }
    }

    private fun parseGeoJson(json: String): RouteResult? {
        return try {
            val root = org.json.JSONObject(json)
            val features = root.getJSONArray("features")
            if (features.length() == 0) return null

            val feature = features.getJSONObject(0)
            val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")

            val points = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                // GeoJSON: [lon, lat] -> нам нужно [lat, lon] для OSMDroid
                points.add(Pair(coord.getDouble(1), coord.getDouble(0)))
            }

            val properties = feature.optJSONObject("properties")
            val distance = properties?.optDouble("distance", 0.0) ?: 0.0
            val time = properties?.optLong("time", 0L) ?: 0L

            RouteResult(points, distance, time)
        } catch (e: Exception) {
            null
        }
    }

    data class RouteResult(
        val points: List<Pair<Double, Double>>, // lat, lon
        val distanceMeters: Double,
        val timeSeconds: Long
    )
}
