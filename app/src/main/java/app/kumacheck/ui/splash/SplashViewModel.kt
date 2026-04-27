package app.kumacheck.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SplashViewModel(
    private val prefs: KumaPrefs,
    private val auth: AuthRepository,
    private val socket: KumaSocket,
) : ViewModel() {

    sealed interface Decision {
        data object Loading : Decision
        data object ToLogin : Decision
        data object ToOverview : Decision
    }

    private val _decision = MutableStateFlow<Decision>(Decision.Loading)
    val decision: StateFlow<Decision> = _decision.asStateFlow()

    init {
        viewModelScope.launch { decide() }
    }

    private suspend fun decide() {
        val url = prefs.serverUrlOnce()
        val token = prefs.tokenOnce()
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            _decision.value = Decision.ToLogin
            return
        }
        try {
            socket.connect(url)
            withTimeout(8_000) {
                socket.connection.first {
                    it == KumaSocket.Connection.LOGIN_REQUIRED ||
                    it == KumaSocket.Connection.CONNECTED ||
                    it == KumaSocket.Connection.AUTHENTICATED
                }
            }
            // If something else (e.g. an active-server switch fired by the
            // app-scope listener) reconnected the socket to a different URL
            // between our `connect(url)` and now, abort: emitting `loginByToken`
            // here would send token-A to server-B, fail, and clear a valid token.
            if (socket.activeUrl != url) {
                _decision.value = Decision.ToLogin
                return
            }
            // Pass `expectedUrl` so the repository re-checks once more
            // immediately before the emit — closes a narrow race where a
            // reconnect lands between the check above and `socket.call` below.
            val result = auth.loginByToken(token, expectedUrl = url)
            _decision.value = if (result is AuthRepository.LoginResult.Success) {
                Decision.ToOverview
            } else {
                // Token rejected (server restart, expiry, password change). Keep
                // username so re-auth needs only password.
                prefs.clearToken()
                Decision.ToLogin
            }
        } catch (t: TimeoutCancellationException) {
            _decision.value = Decision.ToLogin
        } catch (t: Throwable) {
            _decision.value = Decision.ToLogin
        }
    }
}
