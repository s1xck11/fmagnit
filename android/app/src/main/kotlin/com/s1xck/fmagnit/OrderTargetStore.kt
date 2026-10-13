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

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, latitude: Double, longitude: Double) {
        prefs(ctx).edit()
            .putFloat(KEY_LAT, latitude.toFloat())
            .putFloat(KEY_LON, longitude.toFloat())
            .putBoolean(KEY_HAS, true)
            .apply()
    }

    fun load(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_HAS, false)) return null
        val lat = p.getFloat(KEY_LAT, 0f).toDouble()
        val lon = p.getFloat(KEY_LON, 0f).toDouble()
        return lat to lon
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}