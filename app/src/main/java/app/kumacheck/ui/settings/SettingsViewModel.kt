package app.kumacheck.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.auth.ServerEntry
import app.kumacheck.data.auth.ThemeMode
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SettingsViewModel(
    private val socket: KumaSocket,
    private val auth: AuthRepository,
    private val prefs: KumaPrefs,
) : ViewModel() {

    data class UiState(
        val serverUrl: String? = null,
        val username: String? = null,
        val connection: KumaSocket.Connection = KumaSocket.Connection.DISCONNECTED,
        val info: KumaSocket.ServerInfo? = null,
        val notificationsEnabled: Boolean = false,
        val quietHoursEnabled: Boolean = false,
        val quietHoursStartMinute: Int = 22 * 60,
        val quietHoursEndMinute: Int = 7 * 60,
        val servers: List<ServerEntry> = emptyList(),
        val activeServerId: Int? = null,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val appVersion: String = APP_VERSION,
        /**
         * True iff at least one stored token is currently held in plaintext
         * because the AndroidKeyStore was unavailable when we tried to encrypt
         * it. Drives a Settings warning banner.
         */
        val tokensStoredPlaintext: Boolean = false,
    )

    // Chunked into typed combines so the field <-> flow mapping is checked at
    // compile time. The vararg `combine` overload would force untyped `values[N]`
    // index access where a silent reorder swaps fields.
    private data class ServerBits(
        val url: String?,
        val username: String?,
        val connection: KumaSocket.Connection,
        val info: KumaSocket.ServerInfo?,
    )
    private data class NotifBits(
        val notificationsEnabled: Boolean,
        val quietEnabled: Boolean,
        val quietStart: Int,
        val quietEnd: Int,
    )
    private data class ServerListBits(
        val servers: List<ServerEntry>,
        val activeId: Int?,
        val themeMode: ThemeMode,
        val tokensStoredPlaintext: Boolean,
    )

    val state: StateFlow<UiState> = run {
        val server = combine(
            prefs.serverUrl, prefs.username, socket.connection, socket.serverInfo,
        ) { url, user, conn, info -> ServerBits(url, user, conn, info) }
        val notif = combine(
            prefs.notificationsEnabled, prefs.quietHoursEnabled,
            prefs.quietHoursStart, prefs.quietHoursEnd,
        ) { enabled, qe, qs, qend -> NotifBits(enabled, qe, qs, qend) }
        val list = combine(
            prefs.servers, prefs.activeServerId, prefs.themeMode, prefs.tokensStoredPlaintext,
        ) { servers, id, theme, plaintext -> ServerListBits(servers, id, theme, plaintext) }
        combine(server, notif, list) { s, n, l ->
            UiState(
                serverUrl = s.url,
                username = s.username,
                connection = s.connection,
                info = s.info,
                notificationsEnabled = n.notificationsEnabled,
                quietHoursEnabled = n.quietEnabled,
                quietHoursStartMinute = n.quietStart,
                quietHoursEndMinute = n.quietEnd,
                servers = l.servers,
                activeServerId = l.activeId,
                themeMode = l.themeMode,
                tokensStoredPlaintext = l.tokensStoredPlaintext,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
    }

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    /**
     * Guards [signOut] against re-entrancy. Two near-simultaneous taps would
     * otherwise both run `auth.logout()` and the second would read the
     * post-pivot state from `prefs.activeServerOnce()` — silently removing
     * the wrong server.
     */
    private val signOutInFlight = AtomicBoolean(false)

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setNotificationsEnabled(enabled)
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setQuietHoursEnabled(enabled) }
    }

    fun setQuietHoursStart(minuteOfDay: Int) {
        viewModelScope.launch { prefs.setQuietHoursStart(minuteOfDay) }
    }

    fun setQuietHoursEnd(minuteOfDay: Int) {
        viewModelScope.launch { prefs.setQuietHoursEnd(minuteOfDay) }
    }

    fun signOut() {
        if (!signOutInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                // Capture the active server id before clearing its session — we
                // need it to filter when looking for a saved server to pivot to.
                val signedOutId = prefs.activeServerOnce()?.id
                auth.logout()
                // If another saved server has stored credentials, switch to it
                // instead of dropping back to the LOGIN screen. Auto-reauth via
                // loginByToken kicks in once the new socket reaches LOGIN_REQUIRED.
                val pivot = prefs.serversOnce()
                    .firstOrNull { it.id != signedOutId && !it.token.isNullOrBlank() }
                if (pivot != null) {
                    auth.switchServer(pivot.id)
                    // Stay in the app — don't trigger _signedOut.
                } else {
                    _signedOut.value = true
                }
            } finally {
                signOutInFlight.set(false)
            }
        }
    }

    fun switchServer(id: Int) {
        viewModelScope.launch { auth.switchServer(id) }
    }

    fun removeServer(id: Int) {
        viewModelScope.launch { prefs.removeServer(id) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    companion object {
        const val APP_VERSION = "0.5.0"
    }
}
