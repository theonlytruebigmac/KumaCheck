package app.kumacheck.notify

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.kumacheck.KumaCheckApp
import app.kumacheck.data.auth.NotificationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wakes up every ~9 min (see [Watchdog]). Checks whether the FGS for the
 * current [NotificationMode] is still alive and restarts it if not. Always
 * re-arms the alarm for the next interval — the alarm is one-shot, the
 * "periodic" cadence comes from the receiver re-scheduling itself.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? KumaCheckApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mode = app.prefs.notificationModeOnce()
                val tokenPresent = !app.prefs.tokenOnce().isNullOrBlank()
                val hasPerm = Notifications.hasPostPermission(context)
                if (!tokenPresent || !hasPerm) {
                    Log.i(TAG, "watchdog skipping (token=$tokenPresent, perm=$hasPerm)")
                    return@launch
                }
                when (mode) {
                    NotificationMode.OFF -> {
                        // Nothing to revive; cancel ourselves.
                        Watchdog.cancel(context)
                        return@launch
                    }
                    NotificationMode.LIVE_MONITORING -> {
                        if (!isServiceRunning(context, MonitorService::class.java.name)) {
                            Log.i(TAG, "MonitorService not running; restarting")
                            runCatching { MonitorService.start(context) }
                                .onFailure { Log.w(TAG, "MonitorService restart failed", it) }
                        }
                    }
                    NotificationMode.INSTANT_NTFY -> {
                        val configured = !app.prefs.ntfyServerUrlOnce().isNullOrBlank() &&
                            !app.prefs.ntfyTopicOnce().isNullOrBlank()
                        if (!configured) {
                            Log.i(TAG, "ntfy not configured; not starting NtfyService")
                            return@launch
                        }
                        if (!isServiceRunning(context, NtfyService::class.java.name)) {
                            Log.i(TAG, "NtfyService not running; restarting")
                            runCatching { NtfyService.start(context) }
                                .onFailure { Log.w(TAG, "NtfyService restart failed", it) }
                        }
                    }
                }
            } finally {
                // Always rearm so we keep checking, even on a no-op tick.
                Watchdog.schedule(context)
                pendingResult.finish()
            }
        }
    }

    /**
     * `getRunningServices` is deprecated and returns only own-package services
     * on Android 8+, but that's exactly what we need: a cheap "is my own
     * service running" check. The deprecation warning is harmless here.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, className: String): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val running = runCatching { am.getRunningServices(Int.MAX_VALUE) }.getOrNull()
            ?: return false
        return running.any {
            it.service.className == className &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || it.foreground)
        }
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
    }
}
