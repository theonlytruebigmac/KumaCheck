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
 *
 * **B3 trade-off (documented per the audit's "document the behaviour"
 * option):** the gap throttles the *count*, not just the *attempt*. That
 * means a genuinely-rejected token whose `loginByToken` returns Error
 * every 2 s — e.g. server-side token revoked while we're still
 * reconnecting — survives longer than `AUTO_REAUTH_FAILURE_LIMIT`
 * suggests, because each failure inside the 5 s window is collapsed
 * into one. The alternative (count-every-failure) wipes valid tokens
 * on transport flap, which is the worse failure mode in practice — see
 * the `rapid failures (within MIN_GAP_MS) count as one` regression test.
 *
 * If the LoginResult ever distinguishes "auth rejected" from "transport
 * error", flip to counting only the auth-rejected case.
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
 * - On every subsequent LOGIN_REQUIRED, fetches the active server's
 *   (token, URL) pair and calls `loginByToken` with both. The URL is
 *   passed through as `expectedUrl` so that if a server-switch lands
 *   between the prefs read and the emit, [AuthRepository.loginByToken]
 *   aborts rather than sending server A's JWT to server B (NW3).
 *   If no auth is stored (signed-out), does nothing.
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
    activeAuthOnce: suspend () -> Pair<String, String>?,
    loginByToken: suspend (token: String, expectedUrl: String) -> AuthRepository.LoginResult,
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
                val auth = activeAuthOnce() ?: return@collect
                val (token, expectedUrl) = auth
                val result = runCatching { loginByToken(token, expectedUrl) }
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
