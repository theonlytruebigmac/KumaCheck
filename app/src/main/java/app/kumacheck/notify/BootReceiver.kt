package app.kumacheck.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.kumacheck.KumaCheckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts [MonitorService] after a device reboot or an app update so users
 * keep getting incident alerts without having to open the app.
 *
 * Conditions checked before starting:
 *  - notifications are enabled in prefs
 *  - we still have a session token (sign-out should not auto-resume)
 *  - POST_NOTIFICATIONS is granted (Android 13+); on older versions it's
 *    granted at install time
 *
 * On Android 12+, MY_PACKAGE_REPLACED is not on the FGS-start allowlist,
 * so the start may throw ForegroundServiceStartNotAllowedException — we
 * swallow it and rely on KumaCheckApp's prefs collector to start the
 * service the next time the user opens the app.
 *
 * Note: on Android 8+ this receiver only fires after the user has launched
 * the app at least once post-install — that's a system-level constraint.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? KumaCheckApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = app.prefs.notificationsEnabledOnce()
                val token = app.prefs.tokenOnce()
                val hasPerm = Notifications.hasPostPermission(context)
                if (enabled && !token.isNullOrBlank() && hasPerm) {
                    Log.i(TAG, "auto-starting MonitorService on $action")
                    tryStartService(context, action)
                } else {
                    Log.i(TAG, "skipping auto-start (enabled=$enabled, hasToken=${!token.isNullOrBlank()}, hasPerm=$hasPerm)")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun tryStartService(context: Context, action: String?) {
        try {
            MonitorService.start(context)
        } catch (e: Throwable) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException for
            // broadcasts that aren't on the allowlist (notably MY_PACKAGE_REPLACED).
            // Swallow it — the next foreground app launch will start the service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "FGS start not allowed for $action; deferring to next app launch")
            } else {
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
