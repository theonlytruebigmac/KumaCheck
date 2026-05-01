package app.kumacheck.notify

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.kumacheck.KumaCheckApp
import app.kumacheck.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground service for [app.kumacheck.data.auth.NotificationMode.INSTANT_NTFY].
 * Holds an HTTP stream open against `<ntfyServerUrl>/<topic>/json` and renders
 * each incoming message as a notification.
 *
 * Why a `specialUse` FGS instead of WorkManager: ntfy's `/json` endpoint is a
 * long-lived chunked HTTP response — once connected, the server pushes a JSON
 * line per message and stays silent in between. Battery cost is dominated by
 * the idle TCP socket, which is near-zero. WorkManager would force a periodic
 * `?poll=1&since=…` fetch with up to 15-minute latency.
 *
 * On Android 14+, `specialUse` has no runtime cap (verified against AOSP
 * `ActiveServices.getTimeLimitForFgsType` — only `dataSync` and
 * `mediaProcessing` get the 6h cap; everything else returns `Long.MAX_VALUE`).
 *
 * Per-monitor mute does NOT apply here: Kuma's ntfy provider doesn't include
 * the monitor id in the payload. Quiet hours still apply (we know the time).
 */
@SuppressLint("MissingPermission")
class NtfyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamJob: Job? = null
    private var configJob: Job? = null

    @Volatile
    private var quietHoursEnabled: Boolean = false
    @Volatile
    private var quietHoursStart: Int = 22 * 60
    @Volatile
    private var quietHoursEnd: Int = 7 * 60

    /** Last ntfy message id seen, used as `since=` on reconnect to avoid replays. */
    @Volatile
    private var lastMessageId: String? = null

    private val client: OkHttpClient by lazy {
        // Read timeout = 0 disables read timeout — required for a long-lived
        // stream that can be silent for hours between messages.
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat(buildForegroundNotification("Listening for alerts"))

        val app = applicationContext as KumaCheckApp

        configJob = scope.launch {
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

        streamJob = scope.launch { runStreamLoop(app) }
    }

    /**
     * Connects to ntfy `/json` and reads JSON lines until the connection
     * fails. On failure, backs off and reconnects. Replaces silently if the
     * configured topic / server changes.
     */
    private suspend fun runStreamLoop(app: KumaCheckApp) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val server = app.prefs.ntfyServerUrlOnce()?.trimEnd('/')
            val topic = app.prefs.ntfyTopicOnce()
            if (server.isNullOrBlank() || topic.isNullOrBlank()) {
                updateForeground("Ntfy not configured")
                delay(NOT_CONFIGURED_RECHECK_MS)
                continue
            }
            updateForeground("Connecting to $topic")
            val urlBuilder = StringBuilder("$server/$topic/json")
            lastMessageId?.let { urlBuilder.append("?since=").append(it) }
            val request = Request.Builder().url(urlBuilder.toString()).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "ntfy stream returned HTTP ${response.code}")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        return@use
                    }
                    backoffMs = INITIAL_BACKOFF_MS
                    updateForeground("Connected · $topic")
                    val source = response.body?.source() ?: return@use
                    while (scope.isActive && !source.exhausted()) {
                        val line = withContext(Dispatchers.IO) { source.readUtf8Line() }
                            ?: break
                        if (line.isBlank()) continue
                        handleLine(line)
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "ntfy stream error; will retry", t)
            }
            if (!scope.isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun handleLine(line: String) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        // Ntfy emits keepalive / open events in addition to messages. Only
        // event="message" carries a notification payload.
        when (obj.optString("event")) {
            "message" -> {}
            "keepalive", "open", "" -> return
            else -> return
        }
        obj.optString("id").takeIf { it.isNotEmpty() }?.let { lastMessageId = it }

        if (quietHoursEnabled && inQuietWindow(quietHoursStart, quietHoursEnd)) return
        if (!Notifications.hasPostPermission(this)) return

        val title = obj.optString("title").takeIf { it.isNotEmpty() }
            ?: "Monitor alert"
        val body = obj.optString("message").takeIf { it.isNotEmpty() }
            ?: "Status changed"
        val priority = obj.optInt("priority", DEFAULT_NTFY_PRIORITY)
        val tags = obj.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()

        // Classify incident vs recovery. Kuma's ntfy provider sets priority
        // higher for DOWN and lower for UP, and uses tag conventions (red /
        // green emoji shortcodes). Fall back to text heuristics so other
        // ntfy-compatible senders also produce sensible output.
        val isRecovery = tags.any { it in RECOVERY_TAGS } ||
            (priority < INCIDENT_PRIORITY_FLOOR && tags.none { it in INCIDENT_TAGS }) ||
            looksLikeRecovery(title, body)

        val channel = if (isRecovery) Notifications.CHANNEL_RECOVERIES
        else Notifications.CHANNEL_INCIDENTS
        val color = if (isRecovery) COLOR_UP else COLOR_DOWN

        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (isRecovery) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                if (isRecovery) NotificationCompat.CATEGORY_STATUS
                else NotificationCompat.CATEGORY_ERROR
            )
            .setAutoCancel(true)
            .setContentIntent(Notifications.openAppIntent(this))
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(
                ntfyMessageNotificationId(obj.optString("id")),
                notification,
            )
        }.onFailure { Log.w(TAG, "ntfy notify failed", it) }
    }

    private fun looksLikeRecovery(title: String, body: String): Boolean {
        val combined = (title + " " + body).lowercase()
        return RECOVERY_KEYWORDS.any { it in combined }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(text: String) {
        runCatching {
            if (Notifications.hasPostPermission(this)) {
                startForegroundCompat(buildForegroundNotification(text))
            }
        }
    }

    private fun buildForegroundNotification(text: String): android.app.Notification {
        return NotificationCompat.Builder(this, Notifications.CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_BRAND)
            .setContentTitle("KumaCheck · ntfy")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openAppIntent(this))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun inQuietWindow(startMin: Int, endMin: Int): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        return if (startMin == endMin) false
        else if (startMin < endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }

    /**
     * Stable per-message notification id derived from the ntfy message id so
     * a redelivery (we reconnect with `since=` and ntfy replays the same
     * message) updates rather than stacks. Falls back to a random positive
     * int if the id is missing.
     */
    private fun ntfyMessageNotificationId(ntfyMsgId: String): Int {
        val base = if (ntfyMsgId.isEmpty()) System.nanoTime().toInt()
        else ntfyMsgId.hashCode()
        // Stay positive and outside the per-monitor / foreground id ranges.
        return NTFY_ID_OFFSET + (base and 0x7FFFFFFF) % NTFY_ID_RANGE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NtfyService"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val NOT_CONFIGURED_RECHECK_MS = 30_000L
        private const val DEFAULT_NTFY_PRIORITY = 3
        private const val INCIDENT_PRIORITY_FLOOR = 4
        private const val NTFY_ID_OFFSET = 200_000
        private const val NTFY_ID_RANGE = 100_000
        private const val COLOR_DOWN = 0xFFC0392B.toInt()
        private const val COLOR_UP = 0xFF3B8C5A.toInt()
        private const val COLOR_BRAND = 0xFFD97757.toInt()
        private val RECOVERY_TAGS = setOf(
            "white_check_mark", "green_circle", "heavy_check_mark", "ok"
        )
        private val INCIDENT_TAGS = setOf(
            "red_circle", "rotating_light", "warning", "x"
        )
        private val RECOVERY_KEYWORDS = listOf(" up", "recovered", "resolved", "back online")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfyService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NtfyService::class.java))
        }
    }
}
