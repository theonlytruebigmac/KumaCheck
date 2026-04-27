package app.kumacheck.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageViewModel(private val socket: KumaSocket) : ViewModel() {

    data class Row(
        val monitor: Monitor,
        val status: MonitorStatus,
        val active: Boolean,
        val toggling: Boolean,
    )

    data class UiState(
        val rows: List<Row> = emptyList(),
    )

    /** Monitor IDs whose pause/resume call is in flight (UI shows the switch as disabled). */
    private val _toggling = MutableStateFlow<Set<Int>>(emptySet())

    val state: StateFlow<UiState> = combine(
        socket.monitors, socket.latestBeat, _toggling,
    ) { monitors, beats, toggling ->
        val rows = monitors.values
            .filter { it.type != "group" }
            .sortedBy { it.name.lowercase() }
            .map { m ->
                val hb = beats[m.id]
                val effectiveActive = m.active && !m.forceInactive
                Row(
                    monitor = m,
                    status = hb?.status
                        ?: if (!effectiveActive) MonitorStatus.PENDING else MonitorStatus.UNKNOWN,
                    active = effectiveActive,
                    toggling = m.id in toggling,
                )
            }
        UiState(rows = rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setActive(id: Int, active: Boolean) {
        if (id in _toggling.value) return
        _toggling.value = _toggling.value + id
        viewModelScope.launch {
            try {
                if (active) socket.resumeMonitor(id) else socket.pauseMonitor(id)
            } finally {
                _toggling.value = _toggling.value - id
            }
        }
    }
}
