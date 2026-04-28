package app.kumacheck.notify

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.kumacheck.KumaCheckApp
import app.kumacheck.data.model.MonitorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that pins the app process so the KumaSocket stays
 * connected while the user is in the background, and converts incoming
 * `important=true` heartbeats into incident / recovery notifications.
 *
 * The socket itself is owned by [KumaCheckApp]; this service is a passive
 * subscriber.
 */
// Every nm.notify in this file is gated by Notifications.hasPostPermission
// (lines 137, 156, 215). Lint can't trace through the helper, so the gate is
// invisible to it — suppress at the class level rather than littering call
// sites with annotations.
@SuppressLint("MissingPermission")
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var beatsJob: Job? = null
    private var monitorsJob: Job? = null
    private var mutedJob: Job? = null
    private var quietJob: Job? = null
    private var notifiedJob: Job? = null

    /**
     * Q5 / O2: dedupe state extracted into [HeartbeatDedupe] so the
     * three-state transition logic and the rollback contract can be
     * unit-tested in pure JVM. The behaviour is identical to the previous
     * inline `synchronized(statusLock) { lastNotifiedStatus.compute(…) }`
     * pattern but the contract is now method-shaped.
     */
    private val dedupe = HeartbeatDedupe()

    /**
     * Live snapshot of "is the user signed in?" — gates notifications so a
     * beat that races a logout (already on the SharedFlow when clearSession
     * runs) doesn't fire a notification or persist a setNotifiedStatus entry
     * against the just-cleared server. Mirrors prefs.token; @Volatile gives
     * publication.
     */
    @Volatile
    private var hasSession: Boolean = false
    private var sessionJob: Job? = null

    /** Live snapshot of muted monitor ids, fed from prefs.mutedMonitorIds. */
    @Volatile
    private var mutedMonitorIds: Set<Int> = emptySet()

    /** Live quiet-hours snapshot. */
    @Volatile
    private var quietHoursEnabled: Boolean = false
    @Volatile
    private var quietHoursStart: Int = 22 * 60
    @Volatile
    private var quietHoursEnd: Int = 7 * 60

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat(Notifications.buildForeground(this, "Monitoring services"))

        val app = applicationContext as KumaCheckApp

        mutedJob = scope.launch {
            app.prefs.mutedMonitorIds.collect { mutedMonitorIds = it }
        }

        sessionJob = scope.launch {
            app.prefs.token.collect { tok -> hasSession = !tok.isNullOrBlank() }
        }

        notifiedJob = scope.launch {
            app.prefs.notifiedStatusEntries.collect { entries ->
                // Replace wholesale on prefs emission. Fires on service start
                // (seeding from disk) and on active-server switch (where we
                // want the new server's per-monitor map). Held under
                // [statusLock] so the clear+forEach can't race with a beat's
                // check+update — without the lock, a beat firing in the
                // empty window between clear() and the first put would see
                // prev=null and double-notify.
                val decoded = entries.mapNotNull { decodeNotifiedEntry(it) }
                dedupe.seed(decoded.toMap())
            }
        }

        quietJob = scope.launch {
            kotlinx.coroutines.flow.combine(
                app.prefs.quietHoursEnabled,
                app.prefs.quietHoursStart,
                app.prefs.quietHoursEnd,
            ) { enabled, start, end -> Triple(enabled, start, end) }
                .collect { (enabled, start, end) ->
                    quietHoursEnabled = enabled
                    quietHoursStart = start
                    quietHoursEnd = end
                }
        }

        monitorsJob = scope.launch {
            app.socket.monitors.collect { map ->
                val count = map.values.count { it.type != "group" }
                val text = if (count == 0) "Monitoring services"
                else "Monitoring $count service${if (count == 1) "" else "s"}"
                // Re-issue via startForeground so the system keeps the
                // notification in its FGS slot (Android 14+ has tightened
                // the rule that the FGS notification can only be updated
                // through the same channel that started it).
                runCatching {
                    if (Notifications.hasPostPermission(this@MonitorService)) {
                        startForegroundCompat(Notifications.buildForeground(this@MonitorService, text))
                    }
                }
            }
        }

        beatsJob = scope.launch {
            app.socket.beats.collect { hb ->
                if (!hb.important) return@collect
                if (hb.status != MonitorStatus.DOWN && hb.status != MonitorStatus.UP) return@collect
                if (hb.monitorId in mutedMonitorIds) return@collect
                if (quietHoursEnabled && inQuietWindow(quietHoursStart, quietHoursEnd)) return@collect
                // Logout-race guard (R2-3): a beat already on the SharedFlow
                // can land after clearSession() — don't notify or persist
                // setNotifiedStatus against the just-cleared server.
                if (!hasSession) return@collect
                val monitor = app.socket.monitors.value[hb.monitorId] ?: return@collect
                val nm = NotificationManagerCompat.from(this@MonitorService)
                if (!Notifications.hasPostPermission(this@MonitorService)) return@collect

                // Atomic dedupe under [statusLock]. Two same-status beats
                // arriving concurrently can't both pass the check before
                // either records the new state, and the seed-load's
                // clear+populate can't slip in between our read and write.
                //
                // Returns the captured prior state so a failed notify can
                // roll the in-memory map back (O2):
                //   null         → duplicate, skip
                //   Some(null)   → first sighting (rollback = remove key)
                //   Some(prev)   → was `prev` (rollback = restore prev)
                //
                // We use a tiny wrapper type since Kotlin's null can't
                // distinguish "skip" from "first sighting was prev=null".
                val captured = dedupe.claimTransition(hb.monitorId, hb.status) ?: return@collect

                // O2: post AND persist; on failure roll the in-memory
                // dedupe state back so the next beat retries this
                // transition instead of getting silently deduped against
                // a notification that never reached the user. Without
                // the rollback, a transient notify failure would also
                // reseed `lastNotifiedStatus` from stale disk on next
                // service restart and fire a duplicate alert.
                runCatching {
                    when (hb.status) {
                        MonitorStatus.DOWN ->
                            nm.notify(
                                Notifications.perMonitorId(monitor.id),
                                Notifications.buildIncident(this@MonitorService, monitor, hb),
                            )
                        MonitorStatus.UP ->
                            nm.notify(
                                Notifications.perMonitorId(monitor.id),
                                Notifications.buildRecovery(this@MonitorService, monitor, hb),
                            )
                        else -> {}
                    }
                    app.prefs.setNotifiedStatus(hb.monitorId, hb.status.name)
                }.onFailure { t ->
                    Log.w(TAG, "notify/persist failed; rolling back dedupe", t)
                    dedupe.rollback(hb.monitorId, captured)
                }
            }
        }
    }

    private fun decodeNotifiedEntry(entry: String): Pair<Int, MonitorStatus>? {
        val sep = entry.indexOf(':')
        if (sep <= 0 || sep == entry.length - 1) return null
        val mid = entry.substring(0, sep).toIntOrNull() ?: return null
        val status = runCatching { MonitorStatus.valueOf(entry.substring(sep + 1)) }
            .getOrNull() ?: return null
        return mid to status
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15+ caps `dataSync` foreground services at ~6 hours cumulative
     * per 24 hours. When the cap is hit the system invokes `onTimeout` and
     * gives us a few seconds to call `stopSelf`, otherwise it kills us
     * forcibly. Post a user-visible notification first so they know to
     * reopen the app and resume monitoring.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timed out (Android 15 dataSync cap); shutting down")
        runCatching {
            val nm = NotificationManagerCompat.from(this)
            if (Notifications.hasPostPermission(this)) {
                nm.notify(
                    Notifications.MONITORING_PAUSED_ID,
                    Notifications.buildMonitoringPaused(this),
                )
            }
        }.onFailure { Log.w(TAG, "monitoring-paused notify failed", it) }
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    /**
     * True if the current local time is inside [start, end). Handles wrap
     * across midnight, e.g. start=22:00 (1320), end=07:00 (420) covers 02:30.
     */
    private fun inQuietWindow(startMin: Int, endMin: Int): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        return if (startMin == endMin) false
        else if (startMin < endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }

    companion object {
        private const val TAG = "MonitorService"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
