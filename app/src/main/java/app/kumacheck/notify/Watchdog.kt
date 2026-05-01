package app.kumacheck.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Schedules an exact alarm that fires every ~9 minutes to check that the
 * active foreground service is still alive and restart it if not.
 *
 * Why exact alarms: Doze caps `setAndAllowWhileIdle` at roughly one fire per
 * 9 min; `setExactAndAllowWhileIdle` punches through that window. Sideloaded
 * distribution lets us declare `USE_EXACT_ALARM` in the manifest (Play Store
 * gates this for non-alarm apps; F-Droid / direct APK install does not).
 *
 * Why a separate watchdog at all: the FGS itself can survive indefinitely on
 * `specialUse`, but OEM battery managers (Xiaomi MIUI, Samsung "Deep sleep",
 * Huawei, OnePlus) routinely kill background processes regardless of what the
 * AOSP API says. The watchdog is the recovery mechanism.
 *
 */
object Watchdog {
    private const val TAG = "Watchdog"
    private const val REQUEST_CODE = 7172
    private const val INTERVAL_MS = 9L * 60 * 1000

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            } else {
                // User revoked SCHEDULE_EXACT_ALARM (Android 14+ allows this).
                // Fall back to inexact + while-idle — fires roughly once per
                // 15 min under Doze, which is still better than nothing.
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            }
        }.onFailure { Log.w(TAG, "watchdog schedule failed", it) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
