package com.s1xck.fmagnit

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * Определяет пакет приложения на переднем плане через UsageStatsManager.
 * Работает на EMUI/HyperOS/MIUI, где Accessibility.getWindows() заблокирован.
 */
object ForegroundDetector {

    /**
     * Возвращает имя пакета приложения, которое последним вышло на передний план
     * за последние [lookbackMs] миллисекунд. null — если определить не удалось.
     */
    fun getForegroundPackage(context: Context, lookbackMs: Long = 10_000L): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager ?: return null

            val end = System.currentTimeMillis()
            val begin = end - lookbackMs

            val events: UsageEvents = usm.queryEvents(begin, end)
            var lastPackage: String? = null
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                ) {
                    lastPackage = event.packageName
                }
            }
            lastPackage
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет, выдано ли приложению разрешение "Использование данных".
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                    as? android.app.AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}