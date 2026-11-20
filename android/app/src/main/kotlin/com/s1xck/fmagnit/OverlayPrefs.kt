package com.s1xck.fmagnit

import android.content.Context
import android.content.SharedPreferences

/**
 * Постоянное хранилище состояния оверлея.
 * Переживает смерть сервиса и перезагрузку телефона.
 */
object OverlayPrefs {

    private const val PREFS_NAME = "fmagnit_overlay_prefs"

    private const val KEY_POINTS_JSON = "points_json"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_SERVICE_ON = "is_service_on"
    private const val KEY_DOOR_CODES_JSON = "door_codes_json"
    private const val KEY_HEADER_WIDTH_PCT = "header_width_pct"
    private const val KEY_TRANSPORT_MODE = "transport_mode"
    private const val KEY_MAP_PROVIDER = "map_provider"
    private const val KEY_TRAFFIC_ENABLED = "traffic_enabled"

    private const val DEFAULT_HEADER_WIDTH_PCT = 65
    private const val DEFAULT_TRANSPORT_MODE = "auto"
    private const val DEFAULT_MAP_PROVIDER = "auto"
    private const val DEFAULT_TRAFFIC_ENABLED = false

    private val TRANSPORT_MODES = listOf("auto", "bicycle", "electric_bike", "pedestrian")
    private val MAP_PROVIDERS = listOf("auto", "osm", "yandex")

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePoints(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_POINTS_JSON, json).apply()
    }

    fun loadPoints(ctx: Context): String {
        return prefs(ctx).getString(KEY_POINTS_JSON, "[]") ?: "[]"
    }

    fun saveActiveId(ctx: Context, id: String?) {
        val e = prefs(ctx).edit()
        if (id == null) e.remove(KEY_ACTIVE_ID) else e.putString(KEY_ACTIVE_ID, id)
        e.apply()
    }

    fun loadActiveId(ctx: Context): String? {
        return prefs(ctx).getString(KEY_ACTIVE_ID, null)
    }

    fun saveRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_IS_RUNNING, running).apply()
    }

    fun loadRunning(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_IS_RUNNING, false)
    }

    fun saveServiceOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SERVICE_ON, on).apply()
    }

    fun loadServiceOn(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_SERVICE_ON, false)
    }

    fun saveDoorCodes(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_DOOR_CODES_JSON, json).apply()
    }

    fun loadDoorCodes(ctx: Context): String {
        return prefs(ctx).getString(KEY_DOOR_CODES_JSON, "[]") ?: "[]"
    }

    fun saveHeaderWidthPct(ctx: Context, pct: Int) {
        prefs(ctx).edit().putInt(KEY_HEADER_WIDTH_PCT, pct.coerceIn(20, 100)).apply()
    }

    fun loadHeaderWidthPct(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_HEADER_WIDTH_PCT, DEFAULT_HEADER_WIDTH_PCT).coerceIn(20, 100)
    }

    fun saveTransportMode(ctx: Context, mode: String) {
        val m = if (TRANSPORT_MODES.contains(mode)) mode else DEFAULT_TRANSPORT_MODE
        prefs(ctx).edit().putString(KEY_TRANSPORT_MODE, m).apply()
    }

    fun loadTransportMode(ctx: Context): String {
        val m = prefs(ctx).getString(KEY_TRANSPORT_MODE, DEFAULT_TRANSPORT_MODE) ?: DEFAULT_TRANSPORT_MODE
        return if (TRANSPORT_MODES.contains(m)) m else DEFAULT_TRANSPORT_MODE
    }

    fun saveMapProvider(ctx: Context, provider: String) {
        val p = if (MAP_PROVIDERS.contains(provider)) provider else DEFAULT_MAP_PROVIDER
        prefs(ctx).edit().putString(KEY_MAP_PROVIDER, p).apply()
    }

    fun loadMapProvider(ctx: Context): String {
        val p = prefs(ctx).getString(KEY_MAP_PROVIDER, DEFAULT_MAP_PROVIDER) ?: DEFAULT_MAP_PROVIDER
        return if (MAP_PROVIDERS.contains(p)) p else DEFAULT_MAP_PROVIDER
    }

    fun saveTrafficEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TRAFFIC_ENABLED, enabled).apply()
    }

    fun loadTrafficEnabled(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_TRAFFIC_ENABLED, DEFAULT_TRAFFIC_ENABLED)
    }
}