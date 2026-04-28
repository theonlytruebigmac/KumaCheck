package app.kumacheck.data.model

/** Status as encoded in Kuma heartbeats: 0=down, 1=up, 2=pending, 3=maintenance. */
enum class MonitorStatus(val code: Int) {
    DOWN(0), UP(1), PENDING(2), MAINTENANCE(3), UNKNOWN(-1);
    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class Monitor(
    val id: Int,
    val name: String,
    val type: String,
    val active: Boolean,
    val forceInactive: Boolean,
    val parent: Int?,
    val tags: List<String>,
    val hostname: String?,
    val url: String?,
    val port: Int?,
    /**
     * P6: previously unmodelled Kuma fields, surfaced now so the UI can
     * filter / display them rather than only round-tripping through
     * `monitorsRaw`. Each one is optional — missing keys parse to null
     * and feature code is expected to handle absence (e.g. an HTTP
     * monitor has no `keyword`; a Docker monitor has no `httpBodyEncoding`).
     */
    val notificationIDList: List<Int>? = null,
    val acceptedStatusCodes: List<String>? = null,
    val keyword: String? = null,
    val expiryNotification: Boolean? = null,
    val maxredirects: Int? = null,
    val ignoreTls: Boolean? = null,
    val httpBodyEncoding: String? = null,
    val authMethod: String? = null,
    val packetSize: Int? = null,
    val gameDig: String? = null,
    val dockerHost: String? = null,
    val dockerContainer: String? = null,
)

/**
 * One heartbeat entry. Note Kuma is inconsistent across event sources:
 *   - `heartbeat` push event uses `monitorID` (camel)
 *   - `getMonitorBeats` data uses `monitor_id` (snake)
 * Parse function handles both.
 */
data class Heartbeat(
    val monitorId: Int,
    val status: MonitorStatus,
    val time: String,
    val msg: String,
    val ping: Double?,
    val important: Boolean,
    /**
     * Server timestamp parsed once at ingest into epoch millis for safe
     * sorting (P2). The raw [time] string survives for display and as a
     * dedupe key. May be null if the server pushed a malformed timestamp;
     * callers ordering by recency should fall back to that case
     * conservatively (e.g. treat null as oldest).
     */
    val timeMs: Long? = null,
)

/**
 * One persisted incident log entry. Recorded whenever the client observes
 * an `important=true` heartbeat with a UP/DOWN status. Stored per-server in
 * DataStore so we can show a real history that survives reconnects, server
 * switches, and process death — independent of the in-memory rolling
 * `recentBeats` window.
 *
 * The monitor name is captured at write time so we can render historical
 * entries cleanly even if the monitor is later renamed or deleted.
 */
data class IncidentLogEntry(
    val monitorId: Int,
    val monitorName: String,
    val status: MonitorStatus,
    /** Epoch milliseconds for the heartbeat. */
    val timestampMs: Long,
    val msg: String,
)

/** Per-monitor live state derived from monitorList + most recent heartbeat. */
data class MonitorState(
    val monitor: Monitor,
    val lastHeartbeat: Heartbeat?,
) {
    val status: MonitorStatus
        get() = lastHeartbeat?.status
            ?: if (!monitor.active || monitor.forceInactive) MonitorStatus.PENDING
            else MonitorStatus.UNKNOWN
}

/** Summary entry returned by Kuma's `getStatusPageList` socket event. */
data class StatusPage(
    val id: Int,
    val slug: String,
    val title: String,
    val description: String?,
    val icon: String?,
    val published: Boolean,
)

data class StatusPageDetail(
    val page: StatusPage,
    val groups: List<StatusPageGroup>,
    val incident: StatusPageIncident?,
)

data class StatusPageGroup(
    val name: String,
    val monitorIds: List<Int>,
)

/**
 * Bootstrap-flavoured severity tag attached to a status page incident.
 * Kuma's wire format ships these as raw strings (the CSS class name);
 * we normalise to an enum so the UI doesn't have to deal with stringly-
 * typed values and unknown future values fall back to [WARNING].
 */
enum class IncidentStyle(val wire: String) {
    INFO("info"),
    WARNING("warning"),
    DANGER("danger"),
    PRIMARY("primary");

    companion object {
        fun from(raw: String?): IncidentStyle =
            raw?.lowercase()?.let { v -> entries.firstOrNull { it.wire == v } } ?: WARNING
    }
}

data class StatusPageIncident(
    val id: Int,
    val title: String,
    val content: String,
    val style: IncidentStyle,
    val createdDate: String?,
    val pin: Boolean,
)
