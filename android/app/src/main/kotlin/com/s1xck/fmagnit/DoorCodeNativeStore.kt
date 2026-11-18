package com.s1xck.fmagnit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Нативное чтение/запись door_codes.json.
 *
 * ВАЖНО: getApplicationDocumentsDirectory() во Flutter на Android — это
 * /data/data/<pkg>/app_flutter (НЕ filesDir!). Поэтому пишем именно туда,
 * чтобы Dart (DoorCodeStorage) и Kotlin видели один и тот же файл.
 *
 * При первом обращении старые коды из filesDir/door_codes.json
 * автоматически переносятся в общий файл (с дедупликацией),
 * старый файл переименовывается в door_codes.json.bak.
 *
 * Формат: [ {id, code, address, latitude, longitude, updatedAt}, ... ]
 */
object DoorCodeNativeStore {

    private const val FILE_NAME = "door_codes.json"
    private const val LEGACY_BACKUP = "door_codes.json.bak"

    private fun file(ctx: Context): File {
        val dir = File(ctx.applicationInfo.dataDir, "app_flutter")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun legacyFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun readArray(f: File): JSONArray {
        return try {
            if (!f.exists()) return JSONArray()
            val raw = f.readText()
            if (raw.isBlank()) JSONArray() else JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun keyOf(o: JSONObject): String {
        val id = o.optString("id", "")
        if (id.isNotEmpty()) return "id:$id"
        val code = o.optString("code", "")
        val lat = String.format(Locale.US, "%.6f", o.optDouble("latitude", 0.0))
        val lon = String.format(Locale.US, "%.6f", o.optDouble("longitude", 0.0))
        return "c:$code|$lat|$lon"
    }

    /** Переносит старые нативные коды в общий файл. Выполняется один раз. */
    private fun ensureMigrated(ctx: Context) {
        try {
            val legacy = legacyFile(ctx)
            if (!legacy.exists()) return

            val target = file(ctx)
            val merged = readArray(target)
            val old = readArray(legacy)

            val seen = HashSet<String>()
            for (i in 0 until merged.length()) {
                seen.add(keyOf(merged.getJSONObject(i)))
            }
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (seen.add(keyOf(o))) merged.put(o)
            }

            target.writeText(merged.toString())

            val bak = File(ctx.filesDir, LEGACY_BACKUP)
            if (bak.exists()) bak.delete()
            legacy.renameTo(bak)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun loadAll(ctx: Context): List<JSONObject> {
        ensureMigrated(ctx)
        return try {
            val arr = readArray(file(ctx))
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(arr.getJSONObject(i))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
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
    @Synchronized
    fun add(
        ctx: Context,
        code: String,
        address: String,
        latitude: Double,
        longitude: Double
    ): String? {
        return try {
            ensureMigrated(ctx)
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

    @Synchronized
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
                Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())
        } catch (_: Exception) {
            java.util.Date().toString()
        }
    }
}