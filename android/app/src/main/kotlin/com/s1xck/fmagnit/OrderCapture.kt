package com.s1xck.fmagnit

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Перехват адреса заказа с экрана Магнита:
 * дедуп по адресу -> чистка хвоста (подъезд/этаж/квартира) ->
 * Nominatim -> OrderTargetStore -> пинок оверлею (карта + блок заказа).
 *
 * force=true (ручная кнопка/ручной ввод) игнорирует дедуп и паузу неудач.
 * Работает полностью нативно, Flutter-движок не нужен.
 */
object OrderCapture {

    private const val PREFS = "fmagnit_order_capture"
    private const val KEY_LAST = "last_address"
    private const val KEY_PENDING = "pending_address"
    private const val KEY_FAIL = "fail_address"
    private const val KEY_FAIL_TS = "fail_ts"
    private const val FAIL_COOLDOWN_MS = 60_000L

    private const val UA =
        "fmagnit/0.1.0 (Android courier overlay; +https://github.com/s1xck11/fmagnit)"

    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun process(ctx: Context, raw: String, force: Boolean = false) {
        val address = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (address.isEmpty()) return

        val p = prefs(ctx)
        // Геокод уже в работе — не плодим потоки.
        if (address == p.getString(KEY_PENDING, null)) return

        if (!force) {
            // Уже перехвачен успешно — не трогаем.
            if (address == p.getString(KEY_LAST, null)) return
            // Недавно не нашли — пауза 60 сек, потом можно снова.
            val failAddr = p.getString(KEY_FAIL, null)
            if (address == failAddr &&
                System.currentTimeMillis() - p.getLong(KEY_FAIL_TS, 0L) < FAIL_COOLDOWN_MS
            ) return
        }

        p.edit().putString(KEY_PENDING, address).apply()
        val app = ctx.applicationContext

        Thread {
            val cleaned = cleanForGeocode(address)
            var point = geocode(cleaned)
            if (point == null && cleaned.startsWith("Россия, ")) {
                point = geocode(cleaned.removePrefix("Россия, "))
            }

            main.post {
                val pp = prefs(app)
                pp.edit().remove(KEY_PENDING).apply()
                if (point != null) {
                    pp.edit().putString(KEY_LAST, address).apply()
                    OrderTargetStore.save(app, point.first, point.second, address)
                    Toast.makeText(app, "Заказ перехвачен: $cleaned", Toast.LENGTH_LONG).show()
                    notifyOverlay(app, point.first, point.second)
                } else {
                    pp.edit()
                        .putString(KEY_FAIL, address)
                        .putLong(KEY_FAIL_TS, System.currentTimeMillis())
                        .apply()
                    try {
                        CrashLogger.logMessage(app, "capture_debug", "geocode fail: $cleaned")
                    } catch (_: Throwable) {}
                    Toast.makeText(app, "Не нашёл адрес на карте: $cleaned", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Отрезает хвост про подъезд/этаж/квартиру — геокоду нужен только дом. */
    private fun cleanForGeocode(raw: String): String {
        val markers = listOf(
            ", подъезд", ",подъезд",
            ", кв.", ", кв ", ", квартира",
            ", этаж", ", домофон", ", код"
        )
        var cut = raw.length
        for (m in markers) {
            val i = raw.indexOf(m, ignoreCase = true)
            if (i >= 0 && i < cut) cut = i
        }
        return raw.substring(0, cut).trim().trimEnd(',').trim()
    }

    private fun geocode(query: String): Pair<Double, Double>? {
        if (query.isEmpty()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=ru&q=" +
                        URLEncoder.encode(query, "UTF-8")
            )
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "ru")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() == 0) null
            else {
                val o = arr.getJSONObject(0)
                o.getDouble("lat") to o.getDouble("lon")
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun notifyOverlay(ctx: Context, lat: Double, lon: Double) {
        if (!OverlayBridge.isOverlayRunning) return
        try {
            val intent = Intent(ctx, OverlayService::class.java).apply {
                setAction(OverlayService.ACTION_ORDER_TARGET)
                putExtra(OverlayService.EXTRA_ORDER_LAT, lat)
                putExtra(OverlayService.EXTRA_ORDER_LON, lon)
            }
            ctx.startService(intent)
        } catch (_: Throwable) {}
    }
}