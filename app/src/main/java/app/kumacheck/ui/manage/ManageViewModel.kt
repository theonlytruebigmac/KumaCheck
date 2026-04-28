package app.kumacheck.ui.manage

import app.kumacheck.util.stateInVm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.data.socket.tryClaimSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    }.stateInVm(this, UiState())

    fun setActive(id: Int, active: Boolean) {
        // V4 / T4: claim the toggling slot atomically via the
        // `tryClaimSlot` extension. Returns false if another caller (a
        // future off-Main path, e.g. auto-toggle) already claimed it —
        // we silently skip rather than dispatching a duplicate RPC.
        if (!_toggling.tryClaimSlot(id)) return
        viewModelScope.launch {
            try {
                if (active) socket.resumeMonitor(id) else socket.pauseMonitor(id)
            } finally {
                _toggling.update { it - id }
            }
        }
    }
}
