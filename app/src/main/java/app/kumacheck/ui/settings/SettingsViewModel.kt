package app.kumacheck.ui.settings

import app.kumacheck.util.stateInVm

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
    private val appVersionName: String,
    private val migrationFailureFlow: kotlinx.coroutines.flow.Flow<String?>,
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
        // ST3: populated from PackageManager at construction time so the
        // displayed version always matches the installed APK without
        // requiring a hand-bumped constant or BuildConfig generation.
        val appVersion: String = "",
        /**
         * True iff at least one stored token is currently held in plaintext
         * because the AndroidKeyStore was unavailable when we tried to encrypt
         * it. Drives a Settings warning banner.
         */
        val tokensStoredPlaintext: Boolean = false,
        /**
         * True iff the most recent attempt to encrypt a token for storage
         * failed. The token is still in memory for this session, but a
         * restart will lose it (S1). Drives a Settings warning banner.
         */
        val keystoreUnavailableForWrite: Boolean = false,
        /**
         * Non-null if a startup migration ([KumaPrefs.migrateTokensIfNeeded]
         * etc.) threw. The user should re-sign-in to recover (O3).
         */
        val migrationFailure: String? = null,
        /**
         * S2: true iff the active server URL is `http://` and the host is
         * not on a recognised private/loopback range. Drives a persistent
         * Settings banner — the login screen warns once at sign-in, but
         * a phishing redirect to `http://attacker.com:3001` would silently
         * work and the user would forget. Banner re-asserts every session.
         */
        val activeServerInsecureCleartext: Boolean = false,
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
    private data class DiagnosticsBits(
        val keystoreUnavailableForWrite: Boolean,
        val migrationFailure: String?,
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
        val diagnostics = combine(
            prefs.keystoreUnavailableForWrite, migrationFailureFlow,
        ) { ks, mig -> DiagnosticsBits(ks, mig) }
        combine(server, notif, list, diagnostics) { s, n, l, d ->
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
                appVersion = appVersionName,
                tokensStoredPlaintext = l.tokensStoredPlaintext,
                keystoreUnavailableForWrite = d.keystoreUnavailableForWrite,
                migrationFailure = d.migrationFailure,
                activeServerInsecureCleartext = isInsecureCleartextUrl(s.url),
            )
        }.stateInVm(this, UiState(appVersion = appVersionName))
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
}

/**
 * S2: classify a server URL as "insecure cleartext" when it's `http://`
 * AND the host doesn't look loopback / link-local / private. We can't
 * eliminate the global cleartext permit (Android's `<domain>` doesn't
 * support CIDR, so IP-literal LAN addresses can't be enumerated), but we
 * can warn at runtime when the user is on a public-looking host. Pure
 * helper, top-level so the unit test (T-prefix in the audit) can poke
 * the rules directly without an Android harness.
 */
internal fun isInsecureCleartextUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    if (!url.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(url).host?.lowercase() }
        .getOrNull() ?: return true  // unparseable + http:// is the worst case
    if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return false
    // Loopback / link-local IPv6.
    if (host == "::1" || host.startsWith("[::1") || host.startsWith("fe80:")) return false
    // RFC1918 IPv4 — first-octet bucket sufficient for the literal forms
    // Kuma users actually paste in.
    val oct = host.split('.')
    if (oct.size == 4 && oct.all { it.toIntOrNull() in 0..255 }) {
        val a = oct[0].toInt(); val b = oct[1].toInt()
        if (a == 10) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 169 && b == 254) return false  // link-local
    }
    // Common LAN-suffix hostnames.
    if (host.endsWith(".lan") || host.endsWith(".local") ||
        host.endsWith(".internal") || host.endsWith(".home")) return false
    return true
}
