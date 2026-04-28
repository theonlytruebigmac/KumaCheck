package app.kumacheck.data.socket

import app.kumacheck.data.model.Heartbeat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Q5 / T1 / T4 / K1: pure-logic helpers extracted from [KumaSocket] event
 * handlers and [app.kumacheck.ui.manage.ManageViewModel] so the rolling
 * window cap, the live-id prune, and the CAS slot-claim are testable
 * without spinning up a real socket or ViewModel scope.
 *
 * Each helper is intentionally a top-level function so call sites stay
 * single-line lambdas and the test surface is just the function under
 * test, not a class hierarchy.
 */
internal object SocketStateHelpers

/**
 * Append [hb] to the per-monitor recent-beats list, keeping only the most
 * recent [cap] entries. Called from `KumaSocket`'s `heartbeat` handler.
 *
 * Pre-fix this was inlined inside the handler; pulled out so the test can
 * verify "the cap drops the oldest beat first" without standing up a
 * Socket.
 */
internal fun appendBeatToRecent(
    recent: Map<Int, List<Heartbeat>>,
    hb: Heartbeat,
    cap: Int,
): Map<Int, List<Heartbeat>> {
    val rolled = (recent[hb.monitorId].orEmpty() + hb).takeLast(cap)
    return recent + (hb.monitorId to rolled)
}

/**
 * Filter a per-monitor map to the set of currently live monitor ids. Used
 * by `KumaSocket`'s `monitorList` handler (K1) to prune `_latestBeat`,
 * `_recentBeats`, `_uptime`, `_avgPing`, `_certInfo` whenever the server
 * pushes a new authoritative monitor list.
 *
 * Pulled out so the K1 prune can be unit-tested directly — see
 * [app.kumacheck.data.socket.SocketStateHelpersTest].
 */
internal fun <V> pruneToLiveMonitorIds(
    map: Map<Int, V>,
    live: Set<Int>,
): Map<Int, V> = map.filterKeys { it in live }

/**
 * V4 CAS slot-claim. Returns true iff [id] was newly added to the
 * underlying set; false if it was already present (the caller should
 * skip the work). Implemented as a bare CAS loop so a pre-empt during
 * the read-then-write window can't dispatch two concurrent toggles.
 */
internal fun MutableStateFlow<Set<Int>>.tryClaimSlot(id: Int): Boolean {
    while (true) {
        val cur = value
        if (id in cur) return false
        if (compareAndSet(cur, cur + id)) return true
    }
}
