package app.kumacheck

import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Max consecutive auto-reauth attempts before we give up and clear the stored token. */
internal const val AUTO_REAUTH_FAILURE_LIMIT = 3

/**
 * Minimum gap between failures we count toward [AUTO_REAUTH_FAILURE_LIMIT]
 * (NW2). Reconnect storms (Wi-Fi handover, server restart, transient ISP
 * failure) can flap LOGIN_REQUIRED many times in a few seconds; without this
 * gap, three back-to-back transport failures would wipe a perfectly valid
 * token and force the user back to the login screen.
 */
internal const val AUTO_REAUTH_FAILURE_MIN_GAP_MS = 5_000L

/**
 * State machine for "after a successful first authentication, silently
 * re-issue `loginByToken` whenever the socket flips back to LOGIN_REQUIRED."
 *
 * Pulled out of [KumaCheckApp.onCreate] so it can be unit-tested without an
 * io.socket dependency. Behaviour:
 *
 * - Stays inert until the socket reaches AUTHENTICATED at least once. The
 *   Splash flow handles the very first sign-in; this loop only kicks in for
 *   reconnects afterwards.
 * - On every subsequent LOGIN_REQUIRED, fetches the stored JWT and calls
 *   `loginByToken`. If no token is stored (signed-out), does nothing.
 * - Tracks consecutive failures (thrown exception OR non-Success result).
 *   Once [AUTO_REAUTH_FAILURE_LIMIT] consecutive attempts have failed,
 *   calls [clearToken] so the user is forced through the login flow on the
 *   next foreground entry instead of pounding the server forever.
 * - A successful AUTHENTICATED transition resets the failure counter.
 *
 * Returns when the upstream connection flow completes.
 */
internal suspend fun runAutoReauthLoop(
    connection: Flow<KumaSocket.Connection>,
    tokenOnce: suspend () -> String?,
    loginByToken: suspend (String) -> AuthRepository.LoginResult,
    clearToken: suspend () -> Unit = {},
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var hasAuthenticatedThisSession = false
    var consecutiveFailures = 0
    var lastFailureMs = 0L
    connection.collect { state ->
        when (state) {
            KumaSocket.Connection.AUTHENTICATED -> {
                hasAuthenticatedThisSession = true
                consecutiveFailures = 0
                lastFailureMs = 0L
            }
            KumaSocket.Connection.LOGIN_REQUIRED -> {
                if (!hasAuthenticatedThisSession) return@collect
                val token = tokenOnce() ?: return@collect
                val result = runCatching { loginByToken(token) }
                val succeeded = result.getOrNull() is AuthRepository.LoginResult.Success
                if (succeeded) {
                    consecutiveFailures = 0
                    lastFailureMs = 0L
                } else {
                    // Filter reconnect-jitter (NW2): only count this failure
                    // toward the give-up budget if the previous one was at
                    // least AUTO_REAUTH_FAILURE_MIN_GAP_MS ago. Three rapid
                    // LOGIN_REQUIRED transitions inside a few seconds are
                    // almost always transport flap, not a bad token.
                    val now = nowMs()
                    if (now - lastFailureMs >= AUTO_REAUTH_FAILURE_MIN_GAP_MS) {
                        consecutiveFailures++
                        lastFailureMs = now
                        if (consecutiveFailures >= AUTO_REAUTH_FAILURE_LIMIT) {
                            clearToken()
                            consecutiveFailures = 0
                            lastFailureMs = 0L
                        }
                    }
                }
            }
            else -> {}
        }
    }
}
