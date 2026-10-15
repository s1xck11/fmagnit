package com.s1xck.fmagnit

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой логгер крашей. Пишет последний exception + stacktrace в SharedPreferences.
 * Нужен, потому что на EMUI без ADB никак не увидеть логи.
 */
object CrashLogger {

    private const val PREFS = "fmagnit_crash_log"
    private const val KEY_LOG = "last_crash"
    private const val KEY_TS = "last_crash_ts"
    private const val KEY_TAG = "last_crash_tag"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun log(ctx: Context, tag: String, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stack = sw.toString()

            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date())

            val entry = "=== $ts [$tag] ===\n$stack"

            prefs(ctx).edit()
                .putString(KEY_LOG, entry)
                .putLong(KEY_TS, System.currentTimeMillis())
                .putString(KEY_TAG, tag)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun logMessage(ctx: Context, tag: String, message: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date())
            val entry = "=== $ts [$tag] ===\n$message"
            prefs(ctx).edit()
                .putString(KEY_LOG, entry)
                .putLong(KEY_TS, System.currentTimeMillis())
                .putString(KEY_TAG, tag)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context): Map<String, Any?>? {
        val p = prefs(ctx)
        val log = p.getString(KEY_LOG, null) ?: return null
        return mapOf(
            "log" to log,
            "timestamp" to p.getLong(KEY_TS, 0L),
            "tag" to p.getString(KEY_TAG, null),
        )
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}