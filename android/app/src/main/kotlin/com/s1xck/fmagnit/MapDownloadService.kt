package com.s1xck.fmagnit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

    private var mapView: MapView? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

            // MapView должен создаваться в UI-потоке — работаем через mainHandler
            mainHandler.post {
                try {
                    if (mapView == null) {
                        mapView = MapView(applicationContext).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setUseDataConnection(true)
                        }
                    }
                    val mv = mapView ?: throw IllegalStateException("MapView is null")

                    val cacheManager = CacheManager(mv)
                    val bbox = BoundingBox(north, east, south, west)

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
                                saveRegion(
                                    applicationContext, name,
                                    south, north, west, east,
                                    minZoom, maxZoom, downloaded
                                )
                                releaseWakeLock()
                                try { stopForeground(true) } catch (_: Exception) {}
                                stopSelf()
                            }

                            override fun onTaskFailed(errors: Int) {
                                lastError = "Не удалось скачать $errors тайлов"
                                finished = true
                                isRunning = false
                                releaseWakeLock()
                                try { stopForeground(true) } catch (_: Exception) {}
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

                            override fun downloadStarted() {}

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
                    try { stopForeground(true) } catch (_: Exception) {}
                    stopSelf()
                }
            }
        } catch (e: Throwable) {
            lastError = e.message
            finished = true
            isRunning = false
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try { mapView?.onDetach() } catch (_: Exception) {}
        mapView = null
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