package app.kumacheck.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MaintenanceListViewModel(private val socket: KumaSocket) : ViewModel() {

    val state: StateFlow<List<KumaSocket.Maintenance>> = socket.maintenance
        .map { map ->
            // Active first, then scheduled, then ended; alphabetical inside each.
            map.values.sortedWith(
                compareByDescending<KumaSocket.Maintenance> { it.active }
                    .thenBy { (it.status ?: "zzz").lowercase() }
                    .thenBy { it.title.lowercase() }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
