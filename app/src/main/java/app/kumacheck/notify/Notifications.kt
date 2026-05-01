package app.kumacheck.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.kumacheck.MainActivity
import app.kumacheck.R
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object Notifications {

    const val CHANNEL_MONITORING = "kuma_monitoring"
    const val CHANNEL_INCIDENTS = "kuma_incidents"
    const val CHANNEL_RECOVERIES = "kuma_recoveries"

    const val FOREGROUND_NOTIFICATION_ID = 1
    private const val PER_MONITOR_ID_OFFSET = 100_000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING, "Monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while KumaCheck watches your monitors."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCIDENTS, "Incidents",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A monitored service has gone down."
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECOVERIES, "Recoveries",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A previously down service has recovered."
            }
        )
    }

    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun perMonitorId(monitorId: Int): Int = PER_MONITOR_ID_OFFSET + monitorId

    // Status accents from the cozy palette. Drives `setColor()` so Android
    // tints the small-icon area to match the design pack's "down" / "up" /
    // brand cards.
    private const val COLOR_DOWN = 0xFFC0392B.toInt()
    private const val COLOR_UP = 0xFF3B8C5A.toInt()
    private const val COLOR_BRAND = 0xFFD97757.toInt()

    fun buildForeground(context: Context, text: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_BRAND)
            .setContentTitle("KumaCheck")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context, monitorId = null))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildIncident(context: Context, monitor: Monitor, hb: Heartbeat): android.app.Notification {
        // Construct our own collapsed body ("type · just now") so the
        // lock-screen view stays terse regardless of whatever Kuma puts in
        // hb.msg (test names like "Flip UP to DOWN" leak through otherwise).
        // The server message goes into the expanded BigText view.
        val body = buildString {
            append(monitor.type.lowercase())
            append(" · just now")
        }
        val expanded = hb.msg.takeIf { it.isNotBlank() } ?: "Service is down."
        return NotificationCompat.Builder(context, CHANNEL_INCIDENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_DOWN)
            .setContentTitle("${monitor.name} is down")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, monitorId = monitor.id))
            .build()
    }

    fun buildRecovery(context: Context, monitor: Monitor, hb: Heartbeat): android.app.Notification {
        val ping = hb.ping?.takeIf { it >= 0 }?.toInt()
        val body = if (ping != null) "Back up · ${ping}ms" else "Back up"
        return NotificationCompat.Builder(context, CHANNEL_RECOVERIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_UP)
            .setContentTitle("${monitor.name} recovered")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, monitorId = monitor.id))
            .build()
    }

    /**
     * Public no-monitor variant for callers (NtfyService, etc.) that need a
     * "tap → open the app" PendingIntent without minting a deep-link nonce.
     * Per-monitor notifications still go through the private overload so the
     * nonce path stays in one place.
     */
    fun openAppIntent(context: Context): PendingIntent = openAppIntent(context, monitorId = null)

    private fun openAppIntent(context: Context, monitorId: Int?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (monitorId != null) {
                putExtra(EXTRA_MONITOR_ID, monitorId)
                putExtra(EXTRA_DEEP_LINK_NONCE, mintNonce(monitorId))
            }
        }
        // Use monitorId (or 0) as request code so each per-monitor PendingIntent
        // is distinct; otherwise FLAG_UPDATE_CURRENT collapses them and every
        // notification opens the same monitor.
        return PendingIntent.getActivity(
            context,
            monitorId ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val EXTRA_MONITOR_ID = "monitor_id"
    const val EXTRA_DEEP_LINK_NONCE = "deep_link_nonce"

    // ----- Deep-link nonce -----
    //
    // MainActivity is exported (it's the LAUNCHER), so any other app on the
    // device can fire `Intent(component=app.kumacheck/.MainActivity)
    // .putExtra("monitor_id", N)` and trick us into deep-linking. We can't
    // unexport the activity, so each notification PendingIntent we mint
    // includes a one-shot random nonce; MainActivity only honours the
    // monitor_id extra when the nonce matches one we issued. Forged intents
    // from other apps lack a valid nonce and are silently downgraded to a
    // plain app launch.
    //
    // The store is in-memory: process death drops every nonce, so a
    // notification posted by an older process incarnation won't deep-link
    // after restart. That's a rare-path UX regression but the security
    // failure mode is fail-closed.

    private data class NonceEntry(val monitorId: Int, val mintedAtMs: Long) {
        /** Encoded form for persistence (KumaPrefs DEEP_LINK_NONCES). */
        fun toStored(nonce: String): String = "$nonce:$monitorId:$mintedAtMs"
        companion object {
            fun fromStored(s: String): Pair<String, NonceEntry>? {
                val parts = s.split(':')
                if (parts.size != 3) return null
                val monitorId = parts[1].toIntOrNull() ?: return null
                val mintedAt = parts[2].toLongOrNull() ?: return null
                return parts[0] to NonceEntry(monitorId, mintedAt)
            }
        }
    }

    private val pendingNonces = ConcurrentHashMap<String, NonceEntry>()
    private val nonceRandom = SecureRandom()
    private const val NONCE_BYTES = 16
    private const val NONCE_TTL_MS = 24L * 60 * 60 * 1000  // 1 day
    private const val NONCE_MAX_ENTRIES = 256

    /**
     * Hook for `KumaPrefs` persistence — set once from `KumaCheckApp.onCreate`.
     * Both callbacks are async (the in-memory store is the hot path); the
     * disk mirror exists so a notification minted in process incarnation A
     * can be honored after the OS kills + restores the app (PD1).
     */
    @Volatile
    private var persistAdd: ((String) -> Unit)? = null
    @Volatile
    private var persistRemove: ((Set<String>) -> Unit)? = null

    /**
     * Wire the persistence callbacks. Called from KumaCheckApp; safe to call
     * before any nonce is minted. If never wired, nonces remain in-memory only
     * (matches the original pre-PD1 behavior).
     */
    fun setNoncePersistence(add: (String) -> Unit, remove: (Set<String>) -> Unit) {
        persistAdd = add
        persistRemove = remove
    }

    /**
     * Hydrate the in-memory store from a previously-persisted snapshot. Called
     * from `KumaCheckApp.onCreate` so a notification tap that survives process
     * death can still find its nonce. Stale (expired) entries are dropped
     * during hydration and signaled back to the persist-remove callback.
     */
    fun hydrateNonces(stored: Set<String>) {
        val nowMs = System.currentTimeMillis()
        val expired = HashSet<String>()
        for (s in stored) {
            val parsed = NonceEntry.fromStored(s) ?: continue
            val (nonce, entry) = parsed
            if (nowMs - entry.mintedAtMs > NONCE_TTL_MS) {
                expired.add(s)
                continue
            }
            pendingNonces.putIfAbsent(nonce, entry)
        }
        if (expired.isNotEmpty()) persistRemove?.invoke(expired)
    }

    private fun mintNonce(monitorId: Int): String {
        // Bound the in-memory store so a flapping monitor over weeks can't
        // accumulate thousands of unconsumed nonces. We sweep TTL-expired
        // entries on every mint (cheap) and, if the map is still over cap,
        // evict the oldest by mint time. Nonces are still single-use; this
        // bound is purely an anti-leak.
        val nowMs = System.currentTimeMillis()
        if (pendingNonces.size >= NONCE_MAX_ENTRIES) sweepNonces(nowMs)
        val bytes = ByteArray(NONCE_BYTES).also { nonceRandom.nextBytes(it) }
        val nonce = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val entry = NonceEntry(monitorId, nowMs)
        pendingNonces[nonce] = entry
        // Mirror to persistent store so a process-death restore can still
        // honor the nonce (PD1). Async; the in-memory write above is what
        // the same-process consume path actually reads.
        persistAdd?.invoke(entry.toStored(nonce))
        return nonce
    }

    private fun sweepNonces(nowMs: Long) {
        val droppedStored = HashSet<String>()
        // Drop expired entries first; capture their stored form for persist removal.
        val expiredKeys = pendingNonces.entries
            .filter { (_, e) -> nowMs - e.mintedAtMs > NONCE_TTL_MS }
            .map { it.key to it.value }
        expiredKeys.forEach { (nonce, entry) ->
            if (pendingNonces.remove(nonce, entry)) droppedStored.add(entry.toStored(nonce))
        }
        if (pendingNonces.size >= NONCE_MAX_ENTRIES) {
            // Still over cap — evict the oldest until under cap. Snapshot the
            // entries to avoid mutating during iteration.
            pendingNonces.entries
                .sortedBy { it.value.mintedAtMs }
                .take(pendingNonces.size - NONCE_MAX_ENTRIES + 1)
                .forEach {
                    if (pendingNonces.remove(it.key, it.value)) droppedStored.add(it.value.toStored(it.key))
                }
        }
        if (droppedStored.isNotEmpty()) persistRemove?.invoke(droppedStored)
    }

    /**
     * Returns true and removes [nonce] from the store iff it was issued for
     * [monitorId] and is within TTL. Any mismatch (forged intent, replay,
     * expired, stale across process restart) returns false without consuming,
     * so brute-force replays don't drain other legitimate nonces.
     */
    fun consumeDeepLinkNonce(nonce: String?, monitorId: Int): Boolean {
        if (nonce.isNullOrEmpty()) return false
        val entry = pendingNonces[nonce] ?: return false
        if (entry.monitorId != monitorId) return false
        if (System.currentTimeMillis() - entry.mintedAtMs > NONCE_TTL_MS) {
            if (pendingNonces.remove(nonce, entry)) {
                persistRemove?.invoke(setOf(entry.toStored(nonce)))
            }
            return false
        }
        val removed = pendingNonces.remove(nonce, entry)
        if (removed) persistRemove?.invoke(setOf(entry.toStored(nonce)))
        return removed
    }

    private const val TEST_NOTIFICATION_ID = 99_999

    /**
     * Post a sample notification through the incidents channel. Used by the
     * "Send test" Settings button to verify channels + permission in one tap.
     * Returns true if posted, false if permission missing.
     */
    // hasPostPermission gate at the top of the function makes the nm.notify
    // below safe; lint can't trace through the helper to see that.
    @SuppressLint("MissingPermission")
    fun sendTest(context: Context): Boolean {
        ensureChannels(context)
        if (!hasPostPermission(context)) return false
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_INCIDENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_BRAND)
            .setContentTitle("Test alert")
            .setContentText("Lock-screen delivery looks good.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "If you saw this on your lock screen, KumaCheck is set up to alert you when a monitor goes down. Tap to open the app."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, monitorId = null))
            .build()
        runCatching { nm.notify(TEST_NOTIFICATION_ID, notif) }
        return true
    }
}
