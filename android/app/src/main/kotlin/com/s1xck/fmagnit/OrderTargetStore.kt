package com.s1xck.fmagnit

import android.content.Context
import android.content.SharedPreferences

/**
 * Хранит координаты заказа, полученные из приложения доставки
 * через intent (google.navigation:, geo: или https://google.com/maps/...).
 */
object OrderTargetStore {

    private const val PREFS = "fmagnit_order_target"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_HAS = "has"
    private const val KEY_TS = "ts"
    private const val KEY_SOURCE_URI = "source_uri"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, latitude: Double, longitude: Double, sourceUri: String? = null) {
        val e = prefs(ctx).edit()
        e.putString(KEY_LAT, latitude.toString())
        e.putString(KEY_LON, longitude.toString())
        e.putBoolean(KEY_HAS, true)
        e.putLong(KEY_TS, System.currentTimeMillis())
        if (sourceUri != null) e.putString(KEY_SOURCE_URI, sourceUri)
        e.apply()
    }

    fun load(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_HAS, false)) return null
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /**
     * Полная информация о последнем перехвате — для отображения в UI.
     */
    fun loadLast(ctx: Context): Map<String, Any?>? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_HAS, false)) return null
        return mapOf(
            "latitude" to p.getString(KEY_LAT, null)?.toDoubleOrNull(),
            "longitude" to p.getString(KEY_LON, null)?.toDoubleOrNull(),
            "timestamp" to p.getLong(KEY_TS, 0L),
            "sourceUri" to p.getString(KEY_SOURCE_URI, null),
        )
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}