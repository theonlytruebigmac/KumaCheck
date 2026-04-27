package app.kumacheck.ui.statuspages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.StatusPage
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StatusPagesListViewModel(
    private val socket: KumaSocket,
) : ViewModel() {

    data class UiState(
        val pages: List<StatusPage> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The 15s "give up" timer. Cancelled the moment a successful load lands
     * via push, fallback fetch, or refresh — without that, a slow refresh
     * after a successful first load can flip the state to "Server didn't
     * return..." even though the user is happily looking at the loaded list.
     */
    private var giveUpJob: Job? = null

    init {
        // Kuma 2.x pushes `statusPageList` once after auth — observe the flow
        // for that path AND for any later live updates.
        viewModelScope.launch {
            socket.statusPages.collect { pages ->
                if (pages != null) {
                    giveUpJob?.cancel()
                    _state.update { it.copy(pages = pages, isLoading = false, error = null) }
                }
            }
        }
        // Fallback: if the push hasn't landed within a short grace window
        // (older Kuma builds, slow socket auth, etc.), fall back to the
        // ack-based getStatusPageList.
        viewModelScope.launch {
            delay(FALLBACK_FETCH_DELAY_MS)
            if (!_state.value.isLoading) return@launch
            runCatching { socket.fetchStatusPageList() }
                .onSuccess { list ->
                    if (_state.value.isLoading) {
                        giveUpJob?.cancel()
                        _state.update { it.copy(pages = list, isLoading = false, error = null) }
                    }
                }
                .onFailure { t ->
                    if (_state.value.isLoading) {
                        _state.update {
                            it.copy(isLoading = false, error = t.message ?: "Couldn't load status pages")
                        }
                    }
                }
        }
        giveUpJob = viewModelScope.launch {
            delay(GIVE_UP_TIMEOUT_MS)
            if (_state.value.isLoading) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Server didn't return any status pages — pull to refresh once you're connected.",
                    )
                }
            }
        }
    }

    fun refresh() {
        // Cancel the give-up timer first — without this, a refresh that runs
        // longer than the remaining give-up window gets its result overwritten
        // by the timeout error.
        giveUpJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { socket.fetchStatusPageList() }
                .onSuccess { list ->
                    _state.update { it.copy(pages = list, isLoading = false, error = null) }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(isLoading = false, error = t.message ?: "Couldn't load status pages")
                    }
                }
        }
    }

    private companion object {
        const val FALLBACK_FETCH_DELAY_MS = 3_000L
        const val GIVE_UP_TIMEOUT_MS = 15_000L
    }
}
