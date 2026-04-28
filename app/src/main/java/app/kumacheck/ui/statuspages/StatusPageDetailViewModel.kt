package app.kumacheck.ui.statuspages

import app.kumacheck.util.stateInVm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorState
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.model.StatusPageDetail
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StatusPageDetailViewModel(
    private val socket: KumaSocket,
    private val slug: String,
) : ViewModel() {

    data class MonitorRow(
        val state: MonitorState,
        /** Status history for the heat strip (oldest → newest). May be empty. */
        val history: List<MonitorStatus>,
    )

    data class Group(
        val name: String,
        val monitors: List<MonitorRow>,
    )

    data class UiState(
        val detail: StatusPageDetail? = null,
        val groups: List<Group> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
    )

    private val _detail = MutableStateFlow<StatusPageDetail?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        listOf(
            _detail,
            socket.monitors,
            socket.latestBeat,
            socket.recentBeats,
            _isLoading,
            _error,
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val detail = values[0] as StatusPageDetail?
        @Suppress("UNCHECKED_CAST")
        val monitors = values[1] as Map<Int, app.kumacheck.data.model.Monitor>
        @Suppress("UNCHECKED_CAST")
        val beats = values[2] as Map<Int, app.kumacheck.data.model.Heartbeat>
        @Suppress("UNCHECKED_CAST")
        val recent = values[3] as Map<Int, List<app.kumacheck.data.model.Heartbeat>>
        val loading = values[4] as Boolean
        val error = values[5] as String?

        UiState(
            detail = detail,
            groups = detail?.groups?.map { g ->
                Group(
                    name = g.name,
                    monitors = g.monitorIds.mapNotNull { id ->
                        val m: Monitor? = monitors[id]
                        m?.let {
                            MonitorRow(
                                state = MonitorState(it, beats[id]),
                                history = recent[id]
                                    ?.takeLast(STRIP_CELLS)
                                    ?.map { hb -> hb.status }
                                    .orEmpty(),
                            )
                        }
                    },
                )
            } ?: emptyList(),
            isLoading = loading,
            error = error,
        )
    }.stateInVm(this, UiState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val d = socket.fetchStatusPage(slug)
                if (d == null) {
                    _error.value = "Status page “$slug” was not found"
                } else {
                    _detail.value = d
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "fetch failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun dismissError() = _error.update { null }

    /** Post a pinned incident on this status page. UI dismisses on success. */
    fun postIncident(
        title: String,
        content: String,
        style: app.kumacheck.data.model.IncidentStyle,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val (ok, msg) = socket.postIncident(slug, title, content, style)
            if (ok) {
                refresh()
                onDone()
            } else {
                _error.value = msg ?: "Server rejected the incident"
            }
        }
    }

    /** Clear the active incident on this status page. */
    fun unpinIncident() {
        viewModelScope.launch {
            val (ok, msg) = socket.unpinIncident(slug)
            if (ok) refresh()
            else _error.value = msg ?: "Failed to unpin incident"
        }
    }

    companion object {
        const val STRIP_CELLS = 30
    }
}
