package com.google.android.apps.maps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.s1xck.fmagnit.ACTION_ORDER_START_MOCK
import com.s1xck.fmagnit.ACTION_POINT_START
import com.s1xck.fmagnit.ACTION_POINT_STOP
import com.s1xck.fmagnit.CrashLogger
import com.s1xck.fmagnit.ForegroundDetector
import com.s1xck.fmagnit.MainAccessibilityService
import com.s1xck.fmagnit.OrderTargetStore
import com.s1xck.fmagnit.OverlayBridge
import com.s1xck.fmagnit.OverlayPrefs
import com.s1xck.fmagnit.OverlayService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.osmdroid.config.Configuration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MapsActivity : FlutterActivity() {

    companion object {
        private const val TAG = "FMAGNIT_INTENT"
        private const val NOTIF_CHANNEL = "fmagnit_intent_debug"
        private const val NOTIF_ID = 7331
    }

    private val mockChannelName = "com.s1xck.fmagnit/mock_location"
    private val overlayChannelName = "com.s1xck.fmagnit/overlay"

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private var locationManager: LocationManager? = null
    private var handler: Handler? = null
    private var pushRunnable: Runnable? = null

    private var baseLat: Double = 0.0
    private var baseLon: Double = 0.0
    private var baseAcc: Double = 3.0
    private var baseAlt: Double = 0.0

    private var displayLat: Double = 0.0
    private var displayLon: Double = 0.0

    private var isMockRunning = false

    private var pointBroadcastReceiver: BroadcastReceiver? = null
    private var overlayChannel: MethodChannel? = null
    private var mockChannel: MethodChannel? = null

    private var lastHandledUri: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        try {
            super.configureFlutterEngine(flutterEngine)
        } catch (e: Throwable) {
            CrashLogger.log(applicationContext, "configureFlutterEngine", e)
            throw e
        }

        try {
            // Инициализация osmdroid
            try {
                Configuration.getInstance().load(
                    applicationContext,
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
                )
                Configuration.getInstance().userAgentValue = packageName
            } catch (e: Throwable) {
                CrashLogger.log(applicationContext, "osmdroid_init", e)
            }

            locationManager =
                applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            handler = Handler(Looper.getMainLooper())

            mockChannel = MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                mockChannelName
            )
            mockChannel?.setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "isMockLocationEnabled" -> result.success(isMockLocationEnabled())
                        "openDeveloperSettings" -> { openDeveloperSettings(); result.success(null) }
                        "isMockRunning" -> result.success(is