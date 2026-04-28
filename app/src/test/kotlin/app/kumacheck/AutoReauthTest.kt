package app.kumacheck

import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoReauthTest {

    private fun harness() = AutoReauthHarness()

    @Test fun `LOGIN_REQUIRED before first AUTHENTICATED is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(0, h.loginCalls)
    }

    @Test fun `LOGIN_REQUIRED after AUTHENTICATED triggers loginByToken`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply { token = "tok-1" }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(1, h.loginCalls)
        assertEquals("tok-1", h.lastToken)
    }

    @Test fun `LOGIN_REQUIRED with no stored token skips loginByToken`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply { token = null }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(0, h.loginCalls)
    }

    @Test fun `subsequent LOGIN_REQUIRED cycles each trigger a fresh loginByToken`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply { token = "tok" }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        // Note: a real reconnect takes us through DISCONNECTED → CONNECTED →
        // LOGIN_REQUIRED. Simulate that here.
        h.connection.value = KumaSocket.Connection.DISCONNECTED
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(2, h.loginCalls)
    }

    @Test fun `loginByToken throwing does not abort the loop`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply {
            token = "tok"
            throwOnLogin = true
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        // StateFlow dedupes — pass through CONNECTED to re-arm a LOGIN_REQUIRED edge.
        h.throwOnLogin = false
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        // Both attempts ran — first threw, second succeeded.
        assertEquals(2, h.loginCalls)
    }

    @Test fun `intermediate states (CONNECTING, CONNECTED, ERROR) do nothing`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply { token = "tok" }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.CONNECTING
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.ERROR
        h.connection.value = KumaSocket.Connection.DISCONNECTED
        job.cancel()
        assertEquals(0, h.loginCalls)
        assertTrue("must require a prior AUTHENTICATED to act", h.lastToken == null)
    }

    @Test fun `clearToken fires after consecutive failures`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply {
            token = "bad-tok"
            loginResult = AuthRepository.LoginResult.Error("invalid token")
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        // Three failed reauth cycles, each spaced past AUTO_REAUTH_FAILURE_MIN_GAP_MS
        // so the NW2 jitter filter doesn't collapse them. Cycle through CONNECTED
        // so StateFlow emits each LOGIN_REQUIRED edge (it dedupes equal values).
        repeat(AUTO_REAUTH_FAILURE_LIMIT) {
            h.nowMs += AUTO_REAUTH_FAILURE_MIN_GAP_MS + 1
            h.connection.value = KumaSocket.Connection.CONNECTED
            h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        }
        job.cancel()
        assertEquals(AUTO_REAUTH_FAILURE_LIMIT, h.loginCalls)
        assertEquals(1, h.clearTokenCalls)
    }

    @Test fun `rapid failures (within MIN_GAP_MS) count as one (NW2 jitter)`() = runTest(UnconfinedTestDispatcher()) {
        // NW2 regression guard: three failures inside the jitter window must
        // NOT trip clearToken. Reconnect storms / Wi-Fi handovers / server
        // restart shouldn't wipe a perfectly valid token.
        val h = harness().apply {
            token = "tok"
            loginResult = AuthRepository.LoginResult.Error("transient")
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        repeat(AUTO_REAUTH_FAILURE_LIMIT * 2) {
            // No nowMs bump — every failure registers at t=0, so only the
            // first should count toward the give-up budget.
            h.connection.value = KumaSocket.Connection.CONNECTED
            h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        }
        job.cancel()
        assertEquals(0, h.clearTokenCalls)
    }

    @Test fun `successful reauth resets the failure counter`() = runTest(UnconfinedTestDispatcher()) {
        val h = harness().apply {
            token = "tok"
            loginResult = AuthRepository.LoginResult.Error("temporary failure")
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        // Two failures (spaced past the jitter gate), a success, then another
        // failure — must not trigger clearToken because the success reset the
        // counter.
        h.nowMs += AUTO_REAUTH_FAILURE_MIN_GAP_MS + 1
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        h.nowMs += AUTO_REAUTH_FAILURE_MIN_GAP_MS + 1
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        h.loginResult = AuthRepository.LoginResult.Success("tok")
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.loginResult = AuthRepository.LoginResult.Error("fail again")
        h.nowMs += AUTO_REAUTH_FAILURE_MIN_GAP_MS + 1
        h.connection.value = KumaSocket.Connection.CONNECTED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(0, h.clearTokenCalls)
    }

    @Test fun `expectedUrl is propagated to loginByToken`() = runTest(UnconfinedTestDispatcher()) {
        // NW3 regression guard: the URL the active server's token belongs
        // to must be passed through so AuthRepository.loginByToken can
        // abort if a server-switch races with the LOGIN_REQUIRED edge.
        val h = harness().apply {
            token = "tok"
            url = "https://kuma.example.com"
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(1, h.loginCalls)
        assertEquals("tok", h.lastToken)
        assertEquals("https://kuma.example.com", h.lastExpectedUrl)
    }

    @Test fun `LOGIN_REQUIRED with stored token but no URL skips loginByToken`() = runTest(UnconfinedTestDispatcher()) {
        // Defensive: the auto-reauth callback must be all-or-nothing on
        // (token, url). A token without a URL means the caller can't supply
        // expectedUrl, so we'd be back to the pre-NW3 silent cross-server
        // emit risk — skip the attempt.
        val h = harness().apply {
            token = "tok"
            url = null
        }
        val job = launch { h.run() }
        h.connection.value = KumaSocket.Connection.AUTHENTICATED
        h.connection.value = KumaSocket.Connection.LOGIN_REQUIRED
        job.cancel()
        assertEquals(0, h.loginCalls)
    }

    private class AutoReauthHarness {
        val connection = MutableStateFlow(KumaSocket.Connection.DISCONNECTED)
        var token: String? = null
        var url: String? = "https://kuma.test"
        var throwOnLogin: Boolean = false
        var loginCalls: Int = 0
        var lastToken: String? = null
        var lastExpectedUrl: String? = null
        var loginResult: AuthRepository.LoginResult? = null
        var clearTokenCalls: Int = 0

        /**
         * Controllable virtual clock — used so [runAutoReauthLoop]'s NW2
         * jitter filter (which gates failures by AUTO_REAUTH_FAILURE_MIN_GAP_MS
         * since the previous failure) doesn't collapse three rapid failures
         * into one. Default starts at 0 and the test stages bumps explicitly.
         */
        var nowMs: Long = 0L

        suspend fun run() = runAutoReauthLoop(
            connection = connection,
            activeAuthOnce = {
                val t = token
                val u = url
                if (t != null && u != null) t to u else null
            },
            loginByToken = { t, u ->
                loginCalls++
                lastToken = t
                lastExpectedUrl = u
                if (throwOnLogin) throw RuntimeException("simulated network failure")
                loginResult ?: AuthRepository.LoginResult.Success(t)
            },
            clearToken = { clearTokenCalls++ },
            nowMs = { nowMs },
        )
    }
}
