package app.kumacheck.ui.monitors

import app.kumacheck.util.stateInVm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.util.parseBeatTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MonitorsViewModel(private val socket: KumaSocket) : ViewModel() {

    enum class Filter { ALL, UP, DOWN, MAINTENANCE, PAUSED }

    data class Row(
        val monitor: Monitor,
        val status: MonitorStatus,
        val lastPingMs: Double?,
        val uptime24h: Double?,  // 0.0..1.0
        val lastBeatMs: Long?,
    )

    data class Folder(
        val id: Int,                 // group monitor id, or 0 for "Ungrouped"
        val name: String,
        val rows: List<Row>,
        val downCount: Int,
    )

    data class UiState(
        val totalMonitorCount: Int = 0,
        val countsByFilter: Map<Filter, Int> = emptyMap(),
        val folders: List<Folder> = emptyList(),
    )

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _expandedFolders = MutableStateFlow<Set<Int>>(emptySet())
    val expandedFolders: StateFlow<Set<Int>> = _expandedFolders.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val state: StateFlow<UiState> = combine(
        socket.monitors,
        socket.latestBeat,
        socket.uptime,
        _filter,
        _query,
    ) { monitors, beats, uptime, filter, query ->
        compute(monitors, beats, uptime, filter, query)
    }.stateInVm(this, UiState())

    fun setFilter(f: Filter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            socket.reconnect()
            kotlinx.coroutines.delay(900)
            _isRefreshing.value = false
        }
    }

    fun toggleFolder(id: Int) {
        _expandedFolders.update { cur ->
            if (id in cur) cur - id else cur + id
        }
    }

    fun isExpandedByDefault(folder: Folder): Boolean {
        // First-render default: expand any folder containing a problem.
        return folder.downCount > 0 || folder.id == 0
    }

    private fun compute(
        monitors: Map<Int, Monitor>,
        beats: Map<Int, Heartbeat>,
        uptimeMap: Map<Int, Map<String, Double>>,
        filter: Filter,
        query: String,
    ): UiState {
        val groups = monitors.values.filter { it.type == "group" }.associateBy { it.id }
        val nonGroup = monitors.values.filter { it.type != "group" }

        // Build per-monitor row + apply filter
        fun statusFor(m: Monitor): MonitorStatus {
            val hb = beats[m.id]
            return hb?.status
                ?: if (!m.active || m.forceInactive) MonitorStatus.PENDING
                else MonitorStatus.UNKNOWN
        }

        val allRows = nonGroup.map { m ->
            Row(
                monitor = m,
                status = statusFor(m),
                lastPingMs = beats[m.id]?.ping,
                uptime24h = uptimeMap[m.id]?.get("24") ?: uptimeMap[m.id]?.get("1"),
                lastBeatMs = beats[m.id]?.let { it.timeMs ?: parseBeatTime(it.time) },
            )
        }

        val countsByFilter = mapOf(
            Filter.ALL to allRows.size,
            Filter.UP to allRows.count { it.status == MonitorStatus.UP },
            Filter.DOWN to allRows.count { it.status == MonitorStatus.DOWN },
            Filter.MAINTENANCE to allRows.count { it.status == MonitorStatus.MAINTENANCE },
            Filter.PAUSED to allRows.count { !it.monitor.active || it.monitor.forceInactive },
        )

        val q = query.trim().lowercase()
        val filtered = allRows
            .filter { keepUnderFilter(it, filter) }
            .filter { row ->
                if (q.isEmpty()) true
                else row.monitor.name.lowercase().contains(q) ||
                    (row.monitor.hostname?.lowercase()?.contains(q) == true) ||
                    (row.monitor.url?.lowercase()?.contains(q) == true)
            }

        // Group by parent. Parentless rows go in folder id=0 ("Ungrouped").
        val byParent = filtered.groupBy { it.monitor.parent ?: 0 }

        val folders = byParent.map { (parentId, rows) ->
            val name = groups[parentId]?.name ?: if (parentId == 0) "Ungrouped" else "Group $parentId"
            Folder(
                id = parentId,
                name = name,
                rows = rows.sortedBy { it.monitor.name },
                downCount = rows.count { it.status == MonitorStatus.DOWN },
            )
        }.sortedWith(
            compareByDescending<Folder> { it.downCount > 0 }.thenBy { it.name.lowercase() }
        )

        return UiState(
            totalMonitorCount = allRows.size,
            countsByFilter = countsByFilter,
            folders = folders,
        )
    }

    private fun keepUnderFilter(row: Row, f: Filter): Boolean = when (f) {
        Filter.ALL -> true
        Filter.UP -> row.status == MonitorStatus.UP
        Filter.DOWN -> row.status == MonitorStatus.DOWN
        Filter.MAINTENANCE -> row.status == MonitorStatus.MAINTENANCE
        Filter.PAUSED -> !row.monitor.active || row.monitor.forceInactive
    }
}
