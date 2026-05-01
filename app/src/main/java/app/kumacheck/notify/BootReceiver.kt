package app.kumacheck.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.kumacheck.KumaCheckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-applies the user's [app.kumacheck.data.auth.NotificationMode] after a
 * device reboot or an app update so users keep getting alerts without having
 * to open the app.
 *
 * Conditions checked before doing anything:
 *  - we still have a session token (sign-out should not auto-resume)
 *  - POST_NOTIFICATIONS is granted (Android 13+); on older versions it's
 *    granted at install time
 *
 * On Android 12+, MY_PACKAGE_REPLACED is not on the FGS-start allowlist, so
 * the FGS launch may throw — [KumaCheckApp.applyNotificationMode] swallows
 * that and the next foreground app launch picks it back up.
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
                val mode = app.prefs.notificationModeOnce()
                val token = app.prefs.tokenOnce()
                val hasPerm = Notifications.hasPostPermission(context)
                Log.i(TAG, "boot: re-applying mode=$mode hasToken=${!token.isNullOrBlank()} hasPerm=$hasPerm")
                app.applyNotificationMode(mode, !token.isNullOrBlank(), hasPerm)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
