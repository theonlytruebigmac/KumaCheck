package app.kumacheck.ui.common

/**
 * Q2: KumaCheck error-channel convention.
 *
 * The audit flagged that errors reach the UI via two routes:
 *
 *  1. **Socket transport errors** are emitted by
 *     [app.kumacheck.data.socket.KumaSocket.errors] (a buffered
 *     [kotlinx.coroutines.flow.SharedFlow]). After O1 these are consumed by
 *     [app.kumacheck.ui.main.MainShell] and surfaced as a snackbar — every
 *     screen sees the same banner regardless of which is in front.
 *
 *  2. **Per-screen RPC failures** (an `editMonitor` returning `ok=false`,
 *     a `loginByToken` throwing on a 401, etc.) are caught locally in each
 *     ViewModel's `viewModelScope.launch { try { … } catch (t) { setError(…) } }`
 *     and stored on that screen's `UiState.error: String?`. The screen
 *     renders the message in a [KumaAccentCard] / [ErrorRetryRow] and
 *     `vm.dismissError()` clears it.
 *
 * **The two routes are intentionally distinct.** Transport errors are
 * cross-cutting (every screen wants to know the socket is down); RPC
 * errors are screen-local (only the form that submitted the failed
 * `editMonitor` cares). Funnelling everything through [KumaSocket.errors]
 * would force every screen to re-derive "is this error meant for me?" via
 * timing or extra metadata — worse than the current split.
 *
 * **When adding a new ViewModel:**
 *  - Convert thrown exceptions inside `viewModelScope.launch { … }` into
 *    `_state.update { it.copy(error = t.message) }`.
 *  - Provide a `fun dismissError() = _state.update { it.copy(error = null) }`.
 *  - Render `ui.error` via [ErrorRetryRow] (when retry-on-failure makes
 *    sense) or a plain `KumaAccentCard` banner.
 *  - Don't tryEmit to `socket.errors` — that flow is owned by KumaSocket
 *    and reserved for transport-layer events.
 *
 * **When adding a new socket-level error path:**
 *  - Emit through `_errors.tryEmit(message)` inside [KumaSocket].
 *  - The MainShell collector will pick it up automatically.
 */
internal object ErrorChannelConvention
