package app.kumacheck.ui.incident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.util.parseBeatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class IncidentDetailViewModel(
    private val socket: KumaSocket,
    private val prefs: KumaPrefs,
    private val monitorId: Int,
) : ViewModel() {

    data class TimelineEvent(
        val timestamp: Long,
        val status: MonitorStatus,
        val title: String,
        val sub: String?,
    )

    data class UiState(
        val monitor: Monitor? = null,
        val isOngoing: Boolean = false,
        /** Start of the most recent DOWN streak, or the most recent recovered DOWN incident's start. */
        val incidentStartMs: Long? = null,
        /** End of the incident — null if still ongoing. */
        val incidentEndMs: Long? = null,
        val incidentMsg: String? = null,
        val downDurationMs: Long = 0,
        val lastSeenMs: Long? = null,
        /** Status grid filling 40 cells, oldest → newest. */
        val strip: List<MonitorStatus> = emptyList(),
        /** Timestamp of the oldest beat backing the strip — null when no real beats are buffered. */
        val stripStartMs: Long? = null,
        val timeline: List<TimelineEvent> = emptyList(),
        /** True when the user has tapped Acknowledge on this incident. */
        val acknowledged: Boolean = false,
    )

    /**
     * 30s ticker so [downDurationMs] increments while the screen is open. The
     * upstream socket flows only re-emit on heartbeat / monitor changes; without
     * a wall-clock pulse, the "Down for Xs" counter would be frozen at the
     * value computed when the last beat landed.
     */
    private val nowTicker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val state: StateFlow<UiState> = combine(
        socket.monitors,
        socket.recentBeats,
        prefs.acknowledgedIncidents,
        nowTicker,
    ) { monitors, recentBeats, ackSet, now ->
        val base = compute(monitors[monitorId], recentBeats[monitorId].orEmpty(), now)
        val ackKey = base.incidentStartMs?.let { "${monitorId}_$it" }
        base.copy(acknowledged = ackKey != null && ackKey in ackSet)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /**
     * Persist the current incident (per-monitor + per-start-time) as
     * acknowledged. Suspends until the prefs write commits — callers that
     * navigate back immediately afterward (IncidentDetailScreen) need this
     * so the parent doesn't re-derive "ongoing incidents" from a stale ack
     * set on resume and momentarily re-show the badge.
     */
    suspend fun acknowledge() {
        val start = state.value.incidentStartMs ?: return
        prefs.acknowledgeIncident(monitorId, start)
    }

    private fun compute(monitor: Monitor?, beats: List<Heartbeat>, now: Long): UiState {
        if (monitor == null || beats.isEmpty()) return UiState(monitor = monitor)

        // Beats are oldest-first.
        val parsed = beats.mapNotNull { hb ->
            parseBeatTime(hb.time)?.let { ts -> hb to ts }
        }.sortedBy { it.second }
        if (parsed.isEmpty()) return UiState(monitor = monitor)

        // Find the latest DOWN streak (most recent contiguous run of DOWN beats).
        val lastIdx = parsed.indices.last()
        val isOngoing = parsed[lastIdx].first.status == MonitorStatus.DOWN
        val streakEndIdx = if (isOngoing) lastIdx else {
            // Walk backwards to the most recent DOWN beat, then back to its streak start.
            val lastDownIdx = (lastIdx downTo 0).firstOrNull {
                parsed[it].first.status == MonitorStatus.DOWN
            } ?: return UiState(
                monitor = monitor,
                strip = strip(parsed),
                stripStartMs = stripStartMs(parsed),
            )
            lastDownIdx
        }
        var streakStartIdx = streakEndIdx
        while (streakStartIdx - 1 >= 0 && parsed[streakStartIdx - 1].first.status == MonitorStatus.DOWN) {
            streakStartIdx--
        }
        val incidentStartMs = parsed[streakStartIdx].second
        val incidentEndMs = if (isOngoing) null else parsed[streakEndIdx].second
        val incidentMsg = parsed[streakEndIdx].first.msg.takeIf { it.isNotBlank() }

        // Last seen UP before the incident.
        val lastSeenMs = (streakStartIdx - 1 downTo 0)
            .firstOrNull { parsed[it].first.status == MonitorStatus.UP }
            ?.let { parsed[it].second }

        val downDurationMs = ((incidentEndMs ?: now) - incidentStartMs).coerceAtLeast(0)

        // Timeline events: notification (proxy: latest important UP/DOWN beat),
        // retries within the streak, first failure, last successful check.
        val timeline = mutableListOf<TimelineEvent>()
        if (isOngoing) {
            timeline.add(
                TimelineEvent(
                    timestamp = parsed[streakEndIdx].second,
                    status = MonitorStatus.PENDING,
                    title = "Notification sent",
                    sub = null,
                )
            )
        }
        // Show up to the 3 most-recent failure beats (post-first), labelled as retries.
        val retries = parsed
            .subList(streakStartIdx + 1, streakEndIdx + 1)
            .reversed()
            .take(3)
        retries.forEachIndexed { idx, (hb, ts) ->
            val n = retries.size - idx
            val total = retries.size + 1
            timeline.add(
                TimelineEvent(
                    timestamp = ts,
                    status = MonitorStatus.DOWN,
                    title = "Retry $n/$total failed",
                    sub = hb.msg.takeIf { it.isNotBlank() },
                )
            )
        }
        timeline.add(
            TimelineEvent(
                timestamp = parsed[streakStartIdx].second,
                status = MonitorStatus.DOWN,
                title = "First failure",
                sub = parsed[streakStartIdx].first.msg.takeIf { it.isNotBlank() },
            )
        )
        if (lastSeenMs != null) {
            val lastSeenIdx = (streakStartIdx - 1 downTo 0)
                .first { parsed[it].first.status == MonitorStatus.UP }
            val ping = parsed[lastSeenIdx].first.ping
            timeline.add(
                TimelineEvent(
                    timestamp = lastSeenMs,
                    status = MonitorStatus.UP,
                    title = "Last successful check",
                    sub = ping?.takeIf { it >= 0 }?.let { "${it.toInt()}ms" },
                )
            )
        }

        return UiState(
            monitor = monitor,
            isOngoing = isOngoing,
            incidentStartMs = incidentStartMs,
            incidentEndMs = incidentEndMs,
            incidentMsg = incidentMsg,
            downDurationMs = downDurationMs,
            lastSeenMs = lastSeenMs,
            strip = strip(parsed),
            stripStartMs = stripStartMs(parsed),
            timeline = timeline.sortedByDescending { it.timestamp },
        )
    }

    private fun strip(parsed: List<Pair<Heartbeat, Long>>, cells: Int = 40): List<MonitorStatus> {
        if (parsed.isEmpty()) return emptyList()
        val tail = parsed.takeLast(cells).map { it.first.status }
        val padding = cells - tail.size
        return if (padding > 0) List(padding) { MonitorStatus.UNKNOWN } + tail else tail
    }

    private fun stripStartMs(parsed: List<Pair<Heartbeat, Long>>, cells: Int = 40): Long? {
        if (parsed.isEmpty()) return null
        return parsed.takeLast(cells).first().second
    }
}
