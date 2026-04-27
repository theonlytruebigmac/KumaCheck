package app.kumacheck.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MaintenanceDetailViewModel(
    private val socket: KumaSocket,
    private val maintenanceId: Int,
) : ViewModel() {

    data class AffectedMonitor(val id: Int, val name: String)

    data class UiState(
        val maintenance: KumaSocket.Maintenance? = null,
        val affectedMonitors: List<AffectedMonitor> = emptyList(),
        val loadingAffected: Boolean = true,
        val toggling: Boolean = false,
        val deleting: Boolean = false,
        val deleted: Boolean = false,
        val error: String? = null,
    )

    private val _toggling = MutableStateFlow(false)
    private val _deleting = MutableStateFlow(false)
    private val _deleted = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _affectedIds = MutableStateFlow<List<Int>>(emptyList())
    private val _loadingAffected = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            try {
                _affectedIds.value = socket.getMonitorMaintenance(maintenanceId)
            } catch (t: Throwable) {
                // Surface the failure so the UI can distinguish "no affected
                // monitors" from "we couldn't ask the server."
                _error.value = t.message ?: "Couldn't load affected monitors"
            } finally {
                _loadingAffected.value = false
            }
        }
    }

    val state: StateFlow<UiState> = combine(
        listOf(
            socket.maintenance.map { it[maintenanceId] },
            socket.monitors,
            _affectedIds,
            _loadingAffected,
            _toggling,
            _deleting,
            _deleted,
            _error,
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            maintenance = values[0] as? KumaSocket.Maintenance,
            affectedMonitors = run {
                @Suppress("UNCHECKED_CAST")
                val monitors = values[1] as Map<Int, app.kumacheck.data.model.Monitor>
                @Suppress("UNCHECKED_CAST")
                val ids = values[2] as List<Int>
                ids.mapNotNull { id ->
                    val name = monitors[id]?.name ?: return@mapNotNull null
                    AffectedMonitor(id, name)
                }.sortedBy { it.name.lowercase() }
            },
            loadingAffected = values[3] as Boolean,
            toggling = values[4] as Boolean,
            deleting = values[5] as Boolean,
            deleted = values[6] as Boolean,
            error = values[7] as? String,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun toggleActive(active: Boolean) {
        if (_toggling.value) return
        _toggling.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val ok = if (active) socket.resumeMaintenance(maintenanceId)
                else socket.pauseMaintenance(maintenanceId)
                if (!ok) _error.value = "Server rejected the request."
            } catch (t: Throwable) {
                _error.value = t.message ?: "unknown error"
            } finally {
                _toggling.value = false
            }
        }
    }

    fun dismissError() { _error.value = null }

    fun delete() {
        if (_deleting.value) return
        _deleting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val (ok, msg) = socket.deleteMaintenance(maintenanceId)
                if (ok) _deleted.value = true
                else _error.value = msg ?: "Failed to delete"
            } catch (t: Throwable) {
                _error.value = t.message ?: "unknown error"
            } finally {
                _deleting.value = false
            }
        }
    }
}
