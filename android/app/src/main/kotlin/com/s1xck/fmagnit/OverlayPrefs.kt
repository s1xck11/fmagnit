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
}