package com.s1xck.fmagnit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Нативное чтение/запись door_codes.json.
 *
 * Файл лежит в filesDir (это то же место, куда Flutter
 * getApplicationDocumentsDirectory() кладёт данные на Android),
 * поэтому Dart и Kotlin видят один и тот же файл.
 *
 * Формат — как в DoorCodeStorage (Dart):
 * [ {id, code, address, latitude, longitude, updatedAt}, ... ]
 */
object DoorCodeNativeStore {

    private const val FILE_NAME = "door_codes.json"

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun loadAll(ctx: Context): List<JSONObject> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val raw = f.readText()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(arr.getJSONObject(i))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(ctx: Context, items: List<JSONObject>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(it) }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /**
     * Добавляет новую запись.
     * @return id новой записи или null, если что-то пошло не так.
     */
    fun add(
        ctx: Context,
        code: String,
        address: String,
        latitude: Double,
        longitude: Double
    ): String? {
        return try {
            val id = UUID.randomUUID().toString()
            val obj = JSONObject().apply {
                put("id", id)
                put("code", code)
                put("address", address)
                put("latitude", latitude)
                put("longitude", longitude)
                put("updatedAt", isoNow())
            }
            val current = loadAll(ctx).toMutableList()
            current.add(obj)
            saveAll(ctx, current)
            id
        } catch (_: Exception) {
            null
        }
    }

    fun asJsonArrayString(ctx: Context): String {
        val arr = JSONArray()
        loadAll(ctx).forEach { arr.put(it) }
        return arr.toString()
    }

    private fun isoNow(): String {
        // Формат совместим с DateTime.parse() во Flutter
        return try {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                java.util.Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())
        } catch (_: Exception) {
            java.util.Date().toString()
        }
    }
}