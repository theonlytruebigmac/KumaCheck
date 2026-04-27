package app.kumacheck.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.util.parseBeatTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MonitorDetailViewModel(
    private val socket: KumaSocket,
    private val prefs: KumaPrefs,
    private val monitorId: Int,
) : ViewModel() {

    data class UiState(
        val monitor: Monitor? = null,
        val latest: Heartbeat? = null,
        val history: List<Heartbeat> = emptyList(),
        val uptime24h: Double? = null,
        val uptime30d: Double? = null,
        val isLoadingHistory: Boolean = true,
        val muted: Boolean = false,
        val deleting: Boolean = false,
        val deleted: Boolean = false,
        val error: String? = null,
        val certInfo: KumaSocket.CertInfo? = null,
    )

    private val _history = MutableStateFlow<List<Heartbeat>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _deleting = MutableStateFlow(false)
    private val _deleted = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        listOf(
            socket.monitors.map { it[monitorId] },
            socket.latestBeat.map { it[monitorId] },
            socket.uptime.map { it[monitorId] },
            _history,
            _isLoading,
            prefs.mutedMonitorIds,
            _deleting,
            _deleted,
            _error,
            socket.certInfo.map { it[monitorId] },
        )
    ) { values ->
        val mon = values[0] as? Monitor
        val latest = values[1] as? Heartbeat
        @Suppress("UNCHECKED_CAST")
        val upMap = values[2] as? Map<String, Double>
        @Suppress("UNCHECKED_CAST")
        val history = values[3] as List<Heartbeat>
        val loading = values[4] as Boolean
        val muted = monitorId in (values[5] as Set<*>)
        UiState(
            monitor = mon,
            latest = latest,
            history = history,
            uptime24h = upMap?.get("24") ?: upMap?.get("1"),
            uptime30d = upMap?.get("720") ?: upMap?.get("30"),
            isLoadingHistory = loading,
            muted = muted,
            deleting = values[6] as Boolean,
            deleted = values[7] as Boolean,
            error = values[8] as? String,
            certInfo = values[9] as? KumaSocket.CertInfo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
        // Live-merge new heartbeats for this monitor into history. Dedupe by
        // parsed timestamp so a beat that races a refresh (server returns it
        // in `fetchMonitorBeats`, then push lands during `_history.update`)
        // doesn't appear twice. String-equality fallback handles beats with
        // unparseable time fields — rare but real on schema drift.
        viewModelScope.launch {
            socket.beats.collect { hb ->
                if (hb.monitorId != monitorId) return@collect
                _history.update { current ->
                    val incomingMs = parseBeatTime(hb.time)
                    val isDuplicate = if (incomingMs != null) {
                        current.any { parseBeatTime(it.time) == incomingMs }
                    } else {
                        current.any { it.time == hb.time }
                    }
                    if (isDuplicate) current
                    else (current + hb).takeLast(MAX_HISTORY)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            val beats = runCatching { socket.fetchMonitorBeats(monitorId, hours = 24) }.getOrDefault(emptyList())
            val serverWindow = beats.takeLast(MAX_HISTORY)
            _history.update { current ->
                // Live beats that arrived during the fetch wouldn't be in
                // serverWindow yet — keep any cached entries whose timestamp
                // is strictly newer than the most recent server beat. Falls
                // back to string-equality dedupe when timestamps don't parse.
                val cutoff = serverWindow.lastOrNull()?.time?.let(::parseBeatTime)
                val seen = serverWindow.mapTo(HashSet()) { it.time }
                val tail = current.filter { hb ->
                    if (hb.time in seen) return@filter false
                    val t = parseBeatTime(hb.time) ?: return@filter true
                    cutoff == null || t > cutoff
                }
                (serverWindow + tail).takeLast(MAX_HISTORY)
            }
            _isLoading.value = false
        }
    }

    fun setMuted(muted: Boolean) {
        viewModelScope.launch {
            val current = prefs.mutedMonitorIds.first().toMutableSet()
            if (muted) current.add(monitorId) else current.remove(monitorId)
            prefs.setMutedMonitorIds(current)
        }
    }

    fun delete() {
        if (_deleting.value) return
        _deleting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val (ok, msg) = socket.deleteMonitor(monitorId)
                if (ok) _deleted.value = true
                else _error.value = msg ?: "Failed to delete monitor"
            } catch (t: Throwable) {
                _error.value = t.message ?: "unknown error"
            } finally {
                _deleting.value = false
            }
        }
    }

    fun dismissError() { _error.value = null }

    companion object {
        private const val MAX_HISTORY = 200
    }
}
