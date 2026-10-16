package com.s1xck.fmagnit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис для скачивания тайлов OSM в локальный кэш.
 * Управляется из Dart через MethodChannel.
 *
 * Хранит прогресс в статических переменных и в SharedPreferences:
 * — список скачанных регионов.
 */
class MapDownloadService : Service() {

    companion object {
        const val TAG = "FMAGNIT_DOWNLOAD"
        const val CHANNEL_ID = "fmagnit_download"
        const val NOTIFICATION_ID = 4202

        const val PREFS_NAME = "fmagnit_downloaded_regions"
        const val KEY_REGIONS = "regions"

        @Volatile var isRunning: Boolean = false
        @Volatile var downloaded: Int = 0
        @Volatile var total: Int = 0
        @Volatile var finished: Boolean = false
        @Volatile var lastError: String? = null
        @Volatile var currentRegionName: String? = null

        private val cancelRequested = AtomicBoolean(false)

        // ---------- API для MethodChannel ----------

        fun start(
            ctx: Context,
            name: String,
            south: Double,
            north: Double,
            west: Double,
            east: Double,
            minZoom: Int,
            maxZoom: Int
        ) {
            if (isRunning) {
                lastError = "Уже идёт другое скачивание"
                return
            }
            cancelRequested.set(false)
            isRunning = true
            downloaded = 0
            total = 0
            finished = false
            lastError = null
            currentRegionName = name

            val intent = Intent(ctx, MapDownloadService::class.java).apply {
                putExtra("name", name)
                putExtra("south", south)
                putExtra("north", north)
                putExtra("west", west)
                putExtra("east", east)
                putExtra("minZoom", minZoom)
                putExtra("maxZoom", maxZoom)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun cancel(ctx: Context) {
            cancelRequested.set(true)
            try {
                ctx.stopService(Intent(ctx, MapDownloadService::class.java))
            } catch (_: Exception) {}
            isRunning = false
            finished = false
            lastError = "Отменено пользователем"
        }

        fun progressMap(): Map<String, Any?> {
            return mapOf(
                "running" to isRunning,
                "downloaded" to downloaded,
                "total" to total,
                "finished" to finished,
                "error" to lastError,
                "regionName" to currentRegionName,
            )
        }

        // ---------- Список сохранённых регионов ----------

        fun listRegions(ctx: Context): List<Map<String, Any?>> {
            val prefs = ctx.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_REGIONS, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<Map<String, Any?>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(mapOf(
                        "id" to o.optString("id"),
                        "name" to o.optString("name"),
                        "south" to o.optDouble("south"),
                        "north" to o.optDouble("north"),
                        "west" to o.optDouble("west"),
                        "east" to o.optDouble("east"),
                        "minZoom" to o.optInt("minZoom"),
                        "maxZoom" to o.optInt("maxZoom"),
                        "tileCount" to o.optInt("tileCount"),
                        "timestamp" to o.optLong("timestamp"),
                    ))
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun deleteRegion(ctx: Context, id: String) {
            try {
                val prefs = ctx.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY_REGIONS, "[]") ?: "[]"
                val arr = JSONArray(raw)
                val newArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("id") != id) newArr.put(o)
                }
                prefs.edit().putString(KEY_REGIONS, newArr.toString()).apply()

                // Удаление тайлов по bbox — просто очищаем всю БД кэша.
                // Точное удаление по bbox в osmdroid сложное, поэтому чистим
                // весь кэш, если удаляют регион (жёстко, но надёжно).
                // Оставлено закомментированным — если нужно.
                // clearTileCache(ctx)
            } catch (_: Exception) {}
        }

        fun clearTileCache(ctx: Context) {
            try {
                val tiles = File(ctx.cacheDir, "osmdroid/tiles")
                if (tiles.exists()) {
                    tiles.deleteRecursively()
                }
            } catch (_: Exception) {}
        }

        private fun saveRegion(
            ctx: Context,
            name: String,
            south: Double,
            north: Double,
            west: Double,
            east: Double,
            minZoom: Int,
            maxZoom: Int,
            tileCount: Int
        ) {
            try {
                val prefs = ctx.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString(KEY_REGIONS, "[]") ?: "[]"
                val arr = JSONArray(raw)

                val id = "${name}_${minZoom}_$maxZoom"
                var replaced = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("id") == id) {
                        o.put("timestamp", System.currentTimeMillis())
                        o.put("tileCount", tileCount)
                        replaced = true
                        break
                    }
                }
                if (!replaced) {
                    val o = JSONObject()
                    o.put("id", id)
                    o.put("name", name)
                    o.put("south", south)
                    o.put("north", north)
                    o.put("west", west)
                    o.put("east", east)
                    o.put("minZoom", minZoom)
                    o.put("maxZoom", maxZoom)
                    o.put("tileCount", tileCount)
                    o.put("timestamp", System.currentTimeMillis())
                    arr.put(o)
                }
                prefs.edit().putString(KEY_REGIONS, arr.toString()).apply()
            } catch (_: Exception) {}
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val name = intent?.getStringExtra("name") ?: "Регион"
            val south = intent?.getDoubleExtra("south", 0.0) ?: 0.0
            val north = intent?.getDoubleExtra("north", 0.0) ?: 0.0
            val west = intent?.getDoubleExtra("west", 0.0) ?: 0.0
            val east = intent?.getDoubleExtra("east", 0.0) ?: 0.0
            val minZoom = intent?.getIntExtra("minZoom", 12) ?: 12
            val maxZoom = intent?.getIntExtra("maxZoom", 17) ?: 17

            acquireWakeLock()
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification(name))

            // Работу делаем в отдельном потоке — CacheManager синхронный
            Thread {
                runDownload(name, south, north, west, east, minZoom, maxZoom)
            }.start()
        } catch (e: Throwable) {
            lastError = e.message
            finished = true
            isRunning = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runDownload(
        name: String,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        minZoom: Int,
        maxZoom: Int
    ) {
        try {
            val cfg = Configuration.getInstance()
            val cacheDir = File(cacheDir, "osmdroid/tiles")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // Создаём writer (SQLite tile cache)
            val writer = SqlTileWriter()

            val cacheManager = CacheManager(writer)

            val bbox = BoundingBox(
                north, east, south, west
            )

            // Считаем примерное количество тайлов
            var approxTotal = 0
            for (z in minZoom..maxZoom) {
                val n = 1 shl z
                val x1 = Math.floor((west + 180.0) / 360.0 * n).toInt()
                val x2 = Math.floor((east + 180.0) / 360.0 * n).toInt()
                val latRad1 = Math.toRadians(north)
                val latRad2 = Math.toRadians(south)
                val y1 = Math.floor(
                    (1.0 - Math.log(Math.tan(latRad1) + 1.0 / Math.cos(latRad1)) / Math.PI) / 2.0 * n
                ).toInt()
                val y2 = Math.floor(
                    (1.0 - Math.log(Math.tan(latRad2) + 1.0 / Math.cos(latRad2)) / Math.PI) / 2.0 * n
                ).toInt()
                approxTotal += (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1)
            }
            total = approxTotal

            cacheManager.downloadAreaAsync(
                this,
                bbox,
                minZoom,
                maxZoom,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        finished = true
                        isRunning = false
                        lastError = null
                        saveRegion(this@MapDownloadService, name, south, north, west, east, minZoom, maxZoom, downloaded)
                        releaseWakeLock()
                        stopForeground(true)
                        stopSelf()
                    }

                    override fun onTaskFailed(errors: Int) {
                        lastError = "Не удалось скачать $errors тайлов"
                        finished = true
                        isRunning = false
                        releaseWakeLock()
                        stopForeground(true)
                        stopSelf()
                    }

                    override fun updateProgress(
                        progress: Int,
                        currentZoomLevel: Int,
                        zoomMin: Int,
                        zoomMax: Int
                    ) {
                        downloaded = progress
                    }

                    override fun downloadStarted() {
                        // ничего
                    }

                    override fun setPossibleTilesInArea(t: Int) {
                        total = t
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed", e)
            lastError = e.message ?: "Неизвестная ошибка"
            finished = true
            isRunning = false
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "fmagnit::download_wakelock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "fmagnit map download", NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(region: String): Notification {
        val openIntent = Intent(this, com.google.android.apps.maps.MapsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Скачивание карты")
            .setContentText(region)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}