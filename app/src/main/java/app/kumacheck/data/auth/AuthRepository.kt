package app.kumacheck.data.auth

import app.kumacheck.data.socket.KumaSocket
import org.json.JSONObject

class AuthRepository(
    private val prefs: KumaPrefs,
    private val socket: KumaSocket,
) {

    sealed interface LoginResult {
        data class Success(val token: String) : LoginResult
        /** Server demands a 2FA TOTP. Re-call with [LoginRequest.token] populated. */
        data object TwoFactorRequired : LoginResult
        data class Error(val msg: String) : LoginResult
    }

    /**
     * v0.1: connection must already be established by the caller (LoginViewModel
     * calls socket.connect first, observes [KumaSocket.connection] flipping to
     * LOGIN_REQUIRED, then calls login()).
     */
    suspend fun login(username: String, password: String, totp: String? = null): LoginResult {
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("token", totp ?: "")
        val resp = runCatching { socket.call("login", payload) }
            .getOrElse { return LoginResult.Error(it.message ?: "network error") }

        // 2FA-required path: Kuma 2.x returns {"tokenRequired": true} with no
        // ok/msg fields. Verified against 2.2.1 by direct probe.
        if (resp.optBoolean("tokenRequired", false)) {
            return LoginResult.TwoFactorRequired
        }

        if (resp.optBoolean("ok", false)) {
            val token = resp.optString("token", "")
            if (token.isNotEmpty()) {
                prefs.setSession(username, token)
                socket.markAuthenticated()
                return LoginResult.Success(token)
            }
            return LoginResult.Error("login ok but no token in response")
        }

        return LoginResult.Error(resp.optString("msg", "unknown error"))
    }

    /**
     * Try to resume a session with a stored JWT. Kuma 2.x replies with `{}` on
     * success (no `ok` field) and with `{"msg": "..."}` on rejection — so we
     * accept either explicit `ok: true` OR a literally empty object as
     * success. A response that is neither (e.g. `{"foo": "bar"}` from a stub
     * proxy or a future Kuma reshape) is treated as rejection rather than
     * silently flipping us to authenticated.
     *
     * Optionally takes [expectedUrl] — the URL the caller intends this token
     * to be authenticated against. If the socket has been reconnected to a
     * different URL between the caller's check and this emit (e.g. an
     * active-server switch racing with Splash), abort before sending: emitting
     * the token to the wrong server would (a) fail and clear a valid token
     * via the normal rejection path, or (b) succeed against an unexpected
     * server.
     */
    suspend fun loginByToken(token: String, expectedUrl: String? = null): LoginResult {
        if (expectedUrl != null && socket.activeUrl != expectedUrl) {
            return LoginResult.Error("server URL changed mid-handshake")
        }
        val resp = runCatching { socket.call("loginByToken", token) }
            .getOrElse {
                android.util.Log.w("AuthRepo", "loginByToken threw: ${it.message}")
                return LoginResult.Error(it.message ?: "network error")
            }
        if (resp.has("msg")) return LoginResult.Error(resp.optString("msg", "token rejected"))
        val explicitOk = resp.optBoolean("ok", false)
        val emptySuccess = resp.length() == 0
        return if (explicitOk || emptySuccess) {
            socket.markAuthenticated()
            LoginResult.Success(token)
        } else {
            LoginResult.Error("token rejected")
        }
    }

    suspend fun logout() {
        prefs.clearSession()
        socket.disconnect()
    }

    /** Use after successful re-login when the JWT was rotated mid-session. */
    suspend fun refreshSocket() {
        socket.reconnect()
    }

    /**
     * Switch the active server and reconnect the socket against the new URL.
     * Caller is responsible for any UI flow afterwards (the auto-reauth
     * listener in KumaCheckApp will pick up `loginByToken` once the socket
     * flips to LOGIN_REQUIRED).
     */
    suspend fun switchServer(id: Int) {
        prefs.setActiveServerId(id)
        // The active-server listener in KumaCheckApp will trigger the actual
        // socket disconnect/reconnect — we don't need to do it here.
    }
}
