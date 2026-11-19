package com.s1xck.fmagnit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Прозрачный трамплин без UI в собственной задаче (singleInstance).
 * Ловит интенты карт от курьерки (мы пакет 2ГИС), парсит координаты,
 * сохраняет таргет, пинает оверлей и УДАЛЯЕТ свою задачу,
 * чтобы foreground вернулся курьерскому приложению.
 */
class OrderIntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Throwable) {
            CrashLogger.log(this, "order_intent", e)
        }
        finishSmart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            handle(intent)
        } catch (e: Throwable) {
            CrashLogger.log(this, "order_intent", e)
        }
        finishSmart()
    }

    private fun finishSmart() {
        try {
            if (isTaskRoot) {
                finishAndRemoveTask()
            } else {
                finish()
                // Страховка: если оказались внутри чужой задачи —
                // возвращаем курьерку на передний план без сброса её стека.
                val li = packageManager.getLaunchIntentForPackage(OverlayBridge.TARGET_PACKAGE)
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(li)
                }
            }
        } catch (_: Throwable) {
            finish()
        }
    }

    private fun handle(i: Intent?) {
        val uri = i?.data
        val dump = buildString {
            append("action=").append(i?.action).append('\n')
            append("data=").append(uri).append('\n')
            append("component=").append(i?.component).append('\n')
            append("extras=").append(i?.extras?.keySet()?.joinToString()).append('\n')
            i?.extras?.keySet()?.forEach { k ->
                append("  ").append(k).append("=").append(i.extras?.get(k)).append('\n')
            }
        }
        CrashLogger.logMessage(this, "order_intent", dump)

        val coords = coordsFromIntent(i)
        if (coords != null) {
            OrderTargetStore.save(this, coords.first, coords.second, uri?.toString())
            Toast.makeText(
                this,
                String.format(Locale.US, "Заказ перехвачен: %.6f, %.6f", coords.first, coords.second),
                Toast.LENGTH_LONG
            ).show()
            notifyOverlay(coords.first, coords.second)
            return
        }

        val address = addressFromIntent(i)
        if (address != null) {
            Toast.makeText(this, "Геокодирую адрес: $address", Toast.LENGTH_LONG).show()
            OrderCapture.process(this, address, force = true)
            return
        }

        Toast.makeText(this, "Интент без координат: $uri", Toast.LENGTH_LONG).show()
    }

    private fun notifyOverlay(lat: Double, lon: Double) {
        if (!OverlayBridge.isOverlayRunning) return
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                setAction(OverlayService.ACTION_ORDER_TARGET)
                putExtra(OverlayService.EXTRA_ORDER_LAT, lat)
                putExtra(OverlayService.EXTRA_ORDER_LON, lon)
            }
            startService(intent)
        } catch (_: Throwable) {}
    }

    private fun coordsFromIntent(i: Intent?): Pair<Double, Double>? {
        val ex = i?.extras
        if (ex != null) {
            val lat = (ex.get("latitude") ?: ex.get("lat"))?.toString()?.toDoubleOrNull()
            val lon = (ex.get("longitude") ?: ex.get("lon"))?.toString()?.toDoubleOrNull()
            if (lat != null && lon != null && abs(lat) <= 90 && abs(lon) <= 180) {
                return lat to lon
            }
        }

        val uri = i?.data ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        val host = uri.host?.lowercase(Locale.US) ?: ""
        val path = uri.path ?: ""
        val is2gis = scheme == "dgis" || host.contains("2gis")

        return when {
            scheme == "geo" ->
                parsePair(uri.schemeSpecificPart.substringBefore('?'), lonFirst = false)
            scheme == "google.navigation" ->
                parsePair(uri.getQueryParameter("q"), lonFirst = false)
            is2gis -> {
                val seg = path.trimEnd('/').substringAfterLast('/')
                parsePair(seg, lonFirst = true)
                    ?: parsePair(uri.getQueryParameter("to") ?: uri.getQueryParameter("q"), lonFirst = true)
            }
            else -> {
                parsePair(uri.getQueryParameter("q"), lonFirst = false)
                    ?: run {
                        val at = Regex("@(-?\\d{1,3}(?:\\.\\d+)?),(-?\\d{1,3}(?:\\.\\d+)?)")
                            .find(uri.toString())
                        if (at != null) parsePair(at.value.removePrefix("@"), lonFirst = false)
                        else parsePair(path.trimEnd('/').substringAfterLast('/'), lonFirst = false)
                    }
            }
        }
    }

    private fun addressFromIntent(i: Intent?): String? {
        val q = i?.data?.getQueryParameter("q")
        if (!q.isNullOrBlank() && q.any { it.isLetter() } && q.length > 6) return q
        val text = i?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank() && text.any { it.isLetter() } && text.length > 6) return text
        return null
    }

    private fun parsePair(raw: String?, lonFirst: Boolean): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)")
            .find(raw) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        if (abs(a) > 180 || abs(b) > 180) return null
        var lat: Double
        var lon: Double
        if (lonFirst) { lon = a; lat = b } else { lat = a; lon = b }
        if (abs(lat) > 90 && abs(lon) <= 90) {
            val t = lat; lat = lon; lon = t
        }
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return lat to lon
    }
}