package com.s1xck.fmagnit

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val OVERLAY_CHANNEL = "com.s1xck.fmagnit/overlay"
    private val OVERLAY_EVENT_CHANNEL = "com.s1xck.fmagnit/overlay_events"
    private val MOCK_CHANNEL = "com.s1xck.fmagnit/mock_location"
    
    private var overlayEventSink: EventChannel.EventSink? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, OVERLAY_EVENT_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { 
                overlayEventSink = events
                sendOverlayState() 
            }
            override fun onCancel(arguments: Any?) { overlayEventSink = null }
        })
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, OVERLAY_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" -> result.success(hasOverlayPermission())
                "requestOverlayPermission" -> { requestOverlayPermission(); result.success(null) }
                "hasAccessibilityPermission" -> result.success(hasAccessibilityPermission())
                "openAccessibilitySettings" -> { openAccessibilitySettings(); result.success(null) }
                "hasUsageAccess" -> result.success(ForegroundDetector.hasUsageAccess(this))
                "openUsageAccessSettings" -> { openUsageAccessSettings(); result.success(null) }
                "getForegroundPackage" -> result.success(ForegroundDetector.getForegroundPackage(this))
                "startOverlay" -> result.success(startOverlayService())
                "stopOverlay" -> result.success(stopOverlayService())
                "isOverlayRunning" -> result.success(OverlayBridge.isOverlayRunning)
                "updatePoints" -> { 
                    val pointsJson = call.argument<String>("pointsJson") ?: "[]"
                    val activeId = call.argument<String>("activeId")
                    updateOverlayPoints(pointsJson, activeId)
                    result.success(null) 
                }
                "updateDoorCodes" -> { 
                    val codesJson = call.argument<String>("codesJson") ?: "[]"
                    updateOverlayDoorCodes(codesJson)
                    result.success(null) 
                }
                "clearOrderTarget" -> { clearOrderTarget(); result.success(null) }
                "setManualOrderTarget" -> { 
                    val lat = call.argument<Double>("latitude") ?: 0.0
                    val lon = call.argument<Double>("longitude") ?: 0.0
                    setManualOrderTarget(lat, lon)
                    result.success(null) 
                }
                else -> result.notImplemented()
            }
        }
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, MOCK_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isMockLocationEnabled" -> result.success(MockManager.isMockLocationEnabled(this))
                "openDeveloperSettings" -> { MockManager.openDeveloperSettings(this); result.success(null) }
                "isMockRunning" -> result.success(MockManager.isRunning())
                "startMock" -> { 
                    val lat = call.argument<Double>("latitude") ?: 0.0
                    val lon = call.argument<Double>("longitude") ?: 0.0
                    val acc = call.argument<Double>("accuracy") ?: 3.0
                    val alt = call.argument<Double>("altitude") ?: 0.0
                    val interval = call.argument<Int>("intervalMs") ?: 1000
                    result.success(MockManager.start(this, lat, lon, acc, alt, interval.toLong())) 
                }
                "updateMock" -> { 
                    val lat = call.argument<Double>("latitude") ?: 0.0
                    val lon = call.argument<Double>("longitude") ?: 0.0
                    val acc = call.argument<Double>("accuracy") ?: 3.0
                    val alt = call.argument<Double>("altitude") ?: 0.0
                    result.success(MockManager.update(this, lat, lon, acc, alt)) 
                }
                "stopMock" -> result.success(MockManager.stop(this))
                "forceResetMock" -> result.success(MockManager.forceReset(this))
                "getRealLocation" -> { 
                    val loc = MockManager.getRealLocation(this)
                    if (loc != null) { 
                        result.success(mapOf(
                            "latitude" to loc.latitude, 
                            "longitude" to loc.longitude, 
                            "accuracy" to loc.accuracy, 
                            "ageMs" to (System.currentTimeMillis() - loc.time)
                        )) 
                    } else { 
                        result.success(null) 
                    } 
                }
                else -> result.notImplemented()
            }
        }
        
        OverlayBridge.onOverlayStateChanged { handler.post { sendOverlayState() } }
    }

    private fun sendOverlayState() {
        if (overlayEventSink != null) { 
            overlayEventSink?.success(mapOf(
                "isRunning" to OverlayBridge.isOverlayRunning, 
                "isForeground" to OverlayBridge.isTargetAppForeground
            )) 
        }
    }

    private fun hasOverlayPermission(): Boolean = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() { 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) 
    }

    private fun hasAccessibilityPermission(): Boolean {
        val service = "$packageName/${MainAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(service, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() { 
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) 
    }

    private fun openUsageAccessSettings() { 
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) 
    }

    private fun startOverlayService(): Boolean { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_START 
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        return true 
    }

    private fun stopOverlayService(): Boolean { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_STOP 
        }
        startService(intent)
        return true 
    }

    private fun updateOverlayPoints(pointsJson: String, activeId: String?) { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_UPDATE_POINTS
            putExtra(OverlayService.EXTRA_POINTS_JSON, pointsJson)
            if (activeId != null) putExtra(OverlayService.EXTRA_ACTIVE_ID, activeId) 
        }
        startService(intent) 
    }

    private fun updateOverlayDoorCodes(codesJson: String) { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_UPDATE_DOOR_CODES
            putExtra(OverlayService.EXTRA_DOOR_CODES_JSON, codesJson) 
        }
        startService(intent) 
    }

    private fun clearOrderTarget() { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_ORDER_TARGET_CLEAR 
        }
        startService(intent) 
    }

    private fun setManualOrderTarget(lat: Double, lon: Double) { 
        val intent = Intent(this, OverlayService::class.java).apply { 
            action = OverlayService.ACTION_ORDER_TARGET
            putExtra(OverlayService.EXTRA_ORDER_LAT, lat)
            putExtra(OverlayService.EXTRA_ORDER_LON, lon) 
        }
        startService(intent) 
    }
}