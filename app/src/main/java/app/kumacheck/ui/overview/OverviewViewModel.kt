package app.kumacheck.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorState
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.util.parseBeatTime
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OverviewViewModel(
    private val socket: KumaSocket,
    private val prefs: KumaPrefs,
) : ViewModel() {

    data class Incident(
        val monitorId: Int,
        val monitorName: String,
        val status: MonitorStatus,
        val timestamp: Long, // epoch millis
        val msg: String?,
    )

    data class ResponseRow(
        val monitor: Monitor,
        val avgPingMs: Int?,
        val recentPings: List<Double>,
        val status: MonitorStatus,
    )

    /**
     * One entry on the cert-expiry banner. `daysRemaining` is null for
     * already-invalid certs (where Kuma reports `valid = false` directly).
     */
    data class CertWarning(
        val monitorId: Int,
        val monitorName: String,
        val daysRemaining: Int?,
        val valid: Boolean,
    )

    data class UiState(
        val total: Int = 0,
        val up: Int = 0,
        val down: Int = 0,
        val maintenance: Int = 0,
        val paused: Int = 0,
        /**
         * Active monitors that haven't reported a heartbeat yet (PENDING) or
         * whose status is unrecognized (UNKNOWN). Without this bucket, those
         * monitors land in `total` but in none of up/down/maint/paused, so the
         * tiles silently sum to less than `total`.
         */
        val pending: Int = 0,
        val avgPingMs: Int? = null,
        val uptime24h: Double? = null,
        val uptime30d: Double? = null,
        val pinnedHeartbeats: List<MonitorState> = emptyList(),
        val allHeartbeats: List<MonitorState> = emptyList(),
        val responseTimes: List<ResponseRow> = emptyList(),
        val activeMaintenance: List<KumaSocket.Maintenance> = emptyList(),
        val recentIncidents: List<Incident> = emptyList(),
        val incidents24h: Int = 0,
        val pinnedIds: Set<Int> = emptySet(),
        val recentStatuses: Map<Int, List<MonitorStatus>> = emptyMap(),
        val certWarnings: List<CertWarning> = emptyList(),
        /** 48-cell heat strip — true 24h binned aggregate (oldest → newest). */
        val heatStrip24h: List<MonitorStatus> = emptyList(),
    )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    @OptIn(FlowPreview::class)
    val state: StateFlow<UiState> = combine(
        listOf(
            socket.monitors,
            socket.latestBeat,
            socket.uptime,
            socket.avgPing,
            socket.maintenance,
            prefs.pinnedMonitorIds,
            socket.recentBeats,
            socket.certInfo,
            prefs.acknowledgedIncidents,
        )
    ) { values ->
        // Pass the array straight through — combine() already allocates a
        // fresh Array<Any?> per emission, the prior copyOf() doubled the
        // allocation cost on every upstream tick. The expensive aggregation
        // (computeOverviewState) runs after a sample(120) below so a burst
        // of socket pushes doesn't trigger a recompute per beat.
        values
    }
        .sample(120)
        .map { values ->
            @Suppress("UNCHECKED_CAST")
            val monitors = values[0] as Map<Int, Monitor>
            @Suppress("UNCHECKED_CAST")
            val beats = values[1] as Map<Int, Heartbeat>
            @Suppress("UNCHECKED_CAST")
            val uptime = values[2] as Map<Int, Map<String, Double>>
            @Suppress("UNCHECKED_CAST")
            val avgPing = values[3] as Map<Int, Double>
            @Suppress("UNCHECKED_CAST")
            val maint = values[4] as Map<Int, KumaSocket.Maintenance>
            @Suppress("UNCHECKED_CAST")
            val pinned = values[5] as Set<Int>
            @Suppress("UNCHECKED_CAST")
            val recentBeats = values[6] as Map<Int, List<Heartbeat>>
            @Suppress("UNCHECKED_CAST")
            val certs = values[7] as Map<Int, KumaSocket.CertInfo>
            @Suppress("UNCHECKED_CAST")
            val ackSet = values[8] as Set<String>

            computeOverviewState(monitors, beats, uptime, avgPing, maint, pinned, recentBeats, certs, ackSet)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            socket.reconnect()
            kotlinx.coroutines.delay(900)
            _isRefreshing.value = false
        }
    }

    fun setPinned(id: Int, pinned: Boolean) {
        viewModelScope.launch {
            val current = state.value.pinnedIds.toMutableSet()
            if (pinned) current.add(id) else current.remove(id)
            prefs.setPinnedMonitorIds(current)
        }
    }

    fun clearPins() {
        viewModelScope.launch { prefs.setPinnedMonitorIds(emptySet()) }
    }
}

private const val MAX_HISTORY = 30
private const val MAX_INCIDENTS = 20
private const val MAX_HEARTBEAT_STRIP = 24
private const val HEATMAP_CELLS = 48
private const val HEATMAP_WINDOW_MS = 24L * 60 * 60 * 1000

/**
 * Pure aggregation step. Lives at the top level (not on the ViewModel) so unit
 * tests can call it without paying the cost of a real socket / prefs harness.
 */
/** Threshold for cert-expiry warning banner (days). */
private const val CERT_WARN_THRESHOLD_DAYS = 30

internal fun computeOverviewState(
    monitors: Map<Int, Monitor>,
    beats: Map<Int, Heartbeat>,
    uptime: Map<Int, Map<String, Double>>,
    avgPing: Map<Int, Double>,
    maintenance: Map<Int, KumaSocket.Maintenance>,
    pinned: Set<Int>,
    recentBeats: Map<Int, List<Heartbeat>>,
    certInfo: Map<Int, KumaSocket.CertInfo> = emptyMap(),
    acknowledgedIncidents: Set<String> = emptySet(),
    nowMs: Long = System.currentTimeMillis(),
): OverviewViewModel.UiState {
    val states = monitors.values
        .filter { it.type != "group" }
        .map { MonitorState(it, beats[it.id]) }
        .sortedBy { it.monitor.name.lowercase() }

    var up = 0; var down = 0; var maint = 0; var paused = 0; var pending = 0
    var pingSum = 0.0; var pingN = 0
    for (s in states) {
        when {
            !s.monitor.active || s.monitor.forceInactive -> paused++
            s.status == MonitorStatus.UP -> up++
            s.status == MonitorStatus.DOWN -> down++
            s.status == MonitorStatus.MAINTENANCE -> maint++
            // Anything else (PENDING — active but no heartbeat yet, or
            // UNKNOWN — unrecognized status code) goes here so up + down +
            // maint + paused + pending always equals total.
            else -> pending++
        }
        s.lastHeartbeat?.ping?.let { p ->
            if (p.isFinite() && p > 0) { pingSum += p; pingN++ }
        }
    }

    val activeStates = states.filter { it.monitor.active && !it.monitor.forceInactive }
    fun aggUptime(keys: List<String>): Double? {
        val vals = activeStates.mapNotNull { st ->
            val u = uptime[st.monitor.id] ?: return@mapNotNull null
            keys.firstNotNullOfOrNull { u[it] }
        }
        return if (vals.isEmpty()) null else vals.average()
    }
    val u24 = aggUptime(listOf("24", "1"))
    val u30 = aggUptime(listOf("720", "30"))

    val responseTimes = states.map { st ->
        val pings = recentBeats[st.monitor.id]
            ?.mapNotNull { it.ping?.takeIf { p -> p >= 0 } }
            ?.takeLast(MAX_HISTORY)
            .orEmpty()
        OverviewViewModel.ResponseRow(
            monitor = st.monitor,
            avgPingMs = avgPing[st.monitor.id]?.toInt() ?: st.lastHeartbeat?.ping?.toInt(),
            recentPings = pings,
            status = st.status,
        )
    }.sortedByDescending { it.avgPingMs ?: 0 }

    val recentStatuses = states.associate { st ->
        st.monitor.id to (recentBeats[st.monitor.id]
            ?.takeLast(MAX_HEARTBEAT_STRIP)
            ?.map { it.status }
            .orEmpty())
    }

    val knownIds = states.map { it.monitor.id }.toSet()
    val pinnedFiltered = pinned.intersect(knownIds)

    val pinnedHeartbeats = if (pinnedFiltered.isEmpty()) emptyList()
    else states.filter { it.monitor.id in pinnedFiltered }

    val incidents = run {
        val out = ArrayList<OverviewViewModel.Incident>()
        recentBeats.forEach { (mid, history) ->
            val name = monitors[mid]?.name ?: return@forEach
            history.forEach { hb ->
                if (!hb.important) return@forEach
                if (hb.status != MonitorStatus.UP && hb.status != MonitorStatus.DOWN) {
                    return@forEach
                }
                val ts = parseBeatTime(hb.time) ?: return@forEach
                out.add(
                    OverviewViewModel.Incident(
                        monitorId = mid,
                        monitorName = name,
                        status = hb.status,
                        timestamp = ts,
                        msg = hb.msg.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        out
            .filter { "${it.monitorId}_${it.timestamp}" !in acknowledgedIncidents }
            .sortedByDescending { it.timestamp }
            .take(MAX_INCIDENTS)
    }
    val cutoff24h = nowMs - 24L * 60 * 60 * 1000
    val incidents24h = incidents.count {
        it.status == MonitorStatus.DOWN && it.timestamp >= cutoff24h
    }

    val certWarnings = run {
        val out = ArrayList<OverviewViewModel.CertWarning>()
        for (st in activeStates) {
            val info = certInfo[st.monitor.id] ?: continue
            val days = info.daysRemaining
            val flagged = !info.valid || (days != null && days <= CERT_WARN_THRESHOLD_DAYS)
            if (!flagged) continue
            out.add(
                OverviewViewModel.CertWarning(
                    monitorId = st.monitor.id,
                    monitorName = st.monitor.name,
                    daysRemaining = days,
                    valid = info.valid,
                )
            )
        }
        // Soonest expiring first; invalid certs (no days) bubble to the top.
        out.sortedWith(compareBy({ it.valid }, { it.daysRemaining ?: Int.MIN_VALUE }))
    }

    // 24h heat strip — true binned aggregate. Each of HEATMAP_CELLS bins covers
    // (HEATMAP_WINDOW_MS / HEATMAP_CELLS) ms of wall-clock time ending at `nowMs`.
    // Per bin, take the worst observed status across every monitor's beats
    // whose timestamp falls in that bin. Bins with no beats stay UNKNOWN.
    val heatStrip24h: List<MonitorStatus> = run {
        val bucketMs = HEATMAP_WINDOW_MS / HEATMAP_CELLS
        val severities = IntArray(HEATMAP_CELLS) // 0 = UNKNOWN
        var sawAny = false
        for ((mid, history) in recentBeats) {
            // Skip group monitors and inactive ones — they shouldn't shift the
            // hero's "is everything ok over the last day?" read.
            val mon = monitors[mid] ?: continue
            if (mon.type == "group") continue
            if (!mon.active || mon.forceInactive) continue
            for (hb in history) {
                val ts = parseBeatTime(hb.time) ?: continue
                val delta = nowMs - ts
                if (delta < 0 || delta >= HEATMAP_WINDOW_MS) continue
                val bin = HEATMAP_CELLS - 1 - (delta / bucketMs).toInt().coerceIn(0, HEATMAP_CELLS - 1)
                val sev = severityOf(hb.status)
                if (sev > severities[bin]) severities[bin] = sev
                sawAny = true
            }
        }
        if (!sawAny) emptyList() else severities.map { statusFromSeverity(it) }
    }

    return OverviewViewModel.UiState(
        total = states.size,
        up = up, down = down, maintenance = maint, paused = paused, pending = pending,
        avgPingMs = if (pingN > 0) (pingSum / pingN).toInt() else null,
        uptime24h = u24,
        uptime30d = u30,
        pinnedHeartbeats = pinnedHeartbeats,
        allHeartbeats = states,
        responseTimes = responseTimes,
        activeMaintenance = maintenance.values
            .filter { it.active }
            .sortedBy { it.title.lowercase() },
        recentIncidents = incidents,
        incidents24h = incidents24h,
        pinnedIds = pinnedFiltered,
        recentStatuses = recentStatuses,
        certWarnings = certWarnings,
        heatStrip24h = heatStrip24h,
    )
}

private fun severityOf(s: MonitorStatus): Int = when (s) {
    MonitorStatus.DOWN -> 3
    MonitorStatus.PENDING, MonitorStatus.MAINTENANCE -> 2
    MonitorStatus.UP -> 1
    MonitorStatus.UNKNOWN -> 0
}

private fun statusFromSeverity(level: Int): MonitorStatus = when (level) {
    3 -> MonitorStatus.DOWN
    2 -> MonitorStatus.PENDING
    1 -> MonitorStatus.UP
    else -> MonitorStatus.UNKNOWN
}
