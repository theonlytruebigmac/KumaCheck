package app.kumacheck.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class LoginViewModel(
    private val auth: AuthRepository,
    private val prefs: KumaPrefs,
    private val socket: KumaSocket,
    /**
     * SavedStateHandle so the *non-secret* form fields (URL, username, the
     * "totpRequired" flag) survive process death. Password and TOTP are NOT
     * persisted — the saved-state bundle is on-disk-cacheable and shouldn't
     * carry credentials. The user re-types those on return.
     */
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    data class State(
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val totp: String = "",
        val totpRequired: Boolean = false,
        val isWorking: Boolean = false,
        val error: String? = null,
        val authenticated: Boolean = false,
    )

    private val _state = MutableStateFlow(
        State(
            serverUrl = savedState.get<String>(KEY_SERVER_URL).orEmpty(),
            username = savedState.get<String>(KEY_USERNAME).orEmpty(),
            totpRequired = savedState.get<Boolean>(KEY_TOTP_REQUIRED) ?: false,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Hydrate from prefs only when SavedStateHandle didn't already
            // carry a URL/username — process-death restore should preserve
            // whatever the user had typed last, not snap back to the saved
            // server.
            val url = savedState.get<String>(KEY_SERVER_URL)
                ?: prefs.serverUrlOnce().orEmpty()
            val username = savedState.get<String>(KEY_USERNAME)
                ?: prefs.username.first().orEmpty()
            _state.update { it.copy(serverUrl = url, username = username) }
        }
    }

    fun onServerUrl(v: String) {
        savedState[KEY_SERVER_URL] = v
        _state.update { it.copy(serverUrl = v, error = null) }
    }
    fun onUsername(v: String) {
        savedState[KEY_USERNAME] = v
        _state.update { it.copy(username = v, error = null) }
    }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onTotp(v: String) = _state.update { it.copy(totp = v.filter { c -> c.isDigit() }.take(6), error = null) }

    fun submit() {
        // Atomic isWorking flip with retry on contention. The CAS guards
        // against the IME `onDone` handler and a button tap firing
        // near-simultaneous submit()s — the naive `if (s.isWorking) return;
        // update { isWorking = true }` lets both pass the guard.
        //
        // Retry on a *non-isWorking* CAS failure handles the case where the
        // user is still typing (onUsername/onPassword fired between snapshot
        // and CAS): we want to honor the submit, not silently drop it. We
        // launch retries via viewModelScope so each attempt yields the
        // dispatcher between iterations — without that, a tight setValue
        // storm during paste could exhaust MAX_SUBMIT_CAS_TRIES inside one
        // dispatcher slice and surface "Try again" to a user who did nothing
        // wrong.
        viewModelScope.launch {
            repeat(MAX_SUBMIT_CAS_TRIES) {
                val s = _state.value
                val rawUrl = s.serverUrl.trim().ifEmpty { return@launch setError("Server URL required") }
                if (!isValidServerUrl(rawUrl)) return@launch setError("Server URL must start with http:// or https:// and include a host")
                // A3: normalize the URL once, here, before it reaches prefs
                // or the socket. Lowercases scheme + host, drops trailing
                // slash, strips default ports. Without this, "https://kuma/"
                // and "https://Kuma" land as two separate server entries.
                val url = normalizeServerUrl(rawUrl)
                val user = s.username.trim().ifEmpty { return@launch setError("Username required") }
                val pass = s.password.ifEmpty { return@launch setError("Password required") }
                if (s.isWorking) return@launch
                if (_state.compareAndSet(s, s.copy(isWorking = true, error = null))) {
                    launchSubmit(url, user, pass, s.totp.takeIf { s.totpRequired && it.isNotEmpty() })
                    return@launch
                }
                kotlinx.coroutines.yield()
            }
            setError("Try again")
        }
    }

    private fun launchSubmit(url: String, user: String, pass: String, totp: String?) {
        viewModelScope.launch {
            try {
                prefs.setServer(url)
                ensureConnectedAndReady(url)
                when (val r = auth.login(user, pass, totp)) {
                    is AuthRepository.LoginResult.Success ->
                        _state.update { it.copy(isWorking = false, authenticated = true) }
                    AuthRepository.LoginResult.TwoFactorRequired -> {
                        savedState[KEY_TOTP_REQUIRED] = true
                        _state.update { it.copy(isWorking = false, totpRequired = true,
                            error = "2FA code required") }
                    }
                    is AuthRepository.LoginResult.Error ->
                        _state.update { it.copy(isWorking = false, error = r.msg) }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isWorking = false, error = t.message ?: "unknown error") }
            }
        }
    }

    private suspend fun ensureConnectedAndReady(url: String) {
        // If the socket is currently against a different URL — even if it's
        // already AUTHENTICATED or LOGIN_REQUIRED — force a reconnect so the
        // subsequent auth.login emits onto the right server (NW3). The
        // round-2 SplashViewModel fix already does this for the splash path;
        // this is the same defense for the live-login path.
        val currentUrl = socket.activeUrl
        val needsConnect = currentUrl != url ||
            socket.connection.value == KumaSocket.Connection.DISCONNECTED ||
            socket.connection.value == KumaSocket.Connection.ERROR
        if (socket.connection.value == KumaSocket.Connection.AUTHENTICATED && currentUrl == url) return
        if (needsConnect) {
            socket.connect(url)
        }
        // Wait until we either get LOGIN_REQUIRED (server signals login needed)
        // or CONNECTED (no autoLogin event in v2 means we can just emit login).
        withTimeout(15_000) {
            socket.connection.first {
                it == KumaSocket.Connection.LOGIN_REQUIRED ||
                it == KumaSocket.Connection.CONNECTED ||
                it == KumaSocket.Connection.AUTHENTICATED
            }
        }
    }

    private fun setError(msg: String) {
        _state.update { it.copy(error = msg) }
    }

    /**
     * Reject anything other than `http://host[...]` / `https://host[...]`.
     * `javascript:`, `file:`, `content:`, `ws:`, scheme-less input, and
     * "https://" with an empty host all fail here so they never reach
     * `socket.connect(url)` or get persisted to prefs.
     */
    private fun isValidServerUrl(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        // Reject embedded user-info ("http://user:pass@host") — both a
        // phishing vector and a likely paste-error. Kuma servers are
        // socket-authenticated, not HTTP-Basic.
        if (parsed.userInfo != null) return false
        val host = parsed.host ?: return false
        return host.isNotBlank()
    }

    /**
     * Apply scheme + host casing rules and trim noise so two visually
     * different inputs that point at the same server collapse to one
     * canonical form. Idempotent on already-normalized URLs. The
     * function assumes the input has already passed [isValidServerUrl].
     *
     * Rules:
     *  - scheme + host lowercased
     *  - trailing slash on the path stripped (keep "/" if it's the only
     *    path char so the URI still parses cleanly)
     *  - default ports dropped: `:80` for http, `:443` for https
     *  - any user-info / query / fragment dropped (rejected by the
     *    validator already, but defensive)
     *
     * Delegates to a top-level helper so unit tests can call it without
     * standing up the full ViewModel + dependencies.
     */
    private fun normalizeServerUrl(url: String): String = normalizeServerUrlImpl(url)

    /**
     * Drop the active server entry if it's an empty placeholder (LOGIN_ADD
     * created via beginAddServer("") and the user backed out before saving).
     * Pivots active back to the most recent remaining server. Safe to call
     * outside the add-server flow — it only acts when URL is blank.
     */
    fun cancelAddIfEmpty() {
        viewModelScope.launch { prefs.cancelAddServer() }
    }

    private companion object {
        /** Cap for the submit() CAS-retry loop — well above any realistic burst. */
        const val MAX_SUBMIT_CAS_TRIES = 8
        const val KEY_SERVER_URL = "loginvm.serverUrl"
        const val KEY_USERNAME = "loginvm.username"
        const val KEY_TOTP_REQUIRED = "loginvm.totpRequired"
    }
}

/**
 * A3: normalize a Kuma server URL into a canonical form so two visually
 * different inputs (`https://kuma/`, `HTTPS://kuma:443`) don't get persisted
 * as separate server entries. Top-level so unit tests can hit it directly.
 *
 * Caller must pass a URL that already validated cleanly (`http://` or
 * `https://` scheme, non-blank host, no embedded user-info). Best-effort
 * on malformed input — returns the input unchanged if `URI` rejects it.
 */
internal fun normalizeServerUrlImpl(url: String): String {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val scheme = parsed.scheme?.lowercase() ?: return url
    val host = parsed.host?.lowercase() ?: return url
    val explicitPort = parsed.port
    val effectivePort = when {
        explicitPort < 0 -> -1
        scheme == "http" && explicitPort == 80 -> -1
        scheme == "https" && explicitPort == 443 -> -1
        else -> explicitPort
    }
    // Strip every trailing slash (including a lone "/") so
    // "https://kuma/" and "https://kuma" collapse to one entry. URIs
    // without a path are valid in the wire format we hand to socket.io.
    val rawPath = (parsed.rawPath ?: "").trimEnd('/')
    val sb = StringBuilder()
    sb.append(scheme).append("://").append(host)
    if (effectivePort >= 0) sb.append(':').append(effectivePort)
    sb.append(rawPath)
    return sb.toString()
}
