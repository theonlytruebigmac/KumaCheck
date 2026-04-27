package app.kumacheck.ui.incident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.model.IncidentLogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reads the per-server persistent incident log so the Incidents tab can show
 * real history (not just the in-memory rolling window). Entries are
 * newest-first; the underlying flow re-emits when the active server changes.
 */
class IncidentsListViewModel(
    private val prefs: KumaPrefs,
) : ViewModel() {

    val incidents: StateFlow<List<IncidentLogEntry>> = prefs.incidentLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() {
        viewModelScope.launch { prefs.clearIncidentLog() }
    }
}
