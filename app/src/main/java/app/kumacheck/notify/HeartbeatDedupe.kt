package app.kumacheck.notify

import app.kumacheck.data.model.MonitorStatus

/**
 * Tiny wrapper used by [HeartbeatDedupe.claimTransition] to distinguish
 * "first sighting" (`PrevStatus(null)`) from "skip — duplicate" (the
 * function returns `null`). Kotlin nullability alone can't carry that
 * three-state outcome.
 */
internal data class PrevStatus(val value: MonitorStatus?)

/**
 * Extracted from [MonitorService] (Q5 / O2 / T4): the per-monitor
 * dedupe map and its rollback semantics. The service used to inline
 * `synchronized(statusLock) { … lastNotifiedStatus[id] = status … }` on
 * every heartbeat; pulling the logic into a class makes:
 *
 *  1. The state-transition rules testable from a pure-JVM unit test
 *     without spinning up a Service.
 *  2. The "rollback after notify failure" guarantee a single method
 *     contract instead of a comment + caller-side discipline.
 *
 * Concurrency contract: every public method synchronises on the same
 * lock. Two concurrent heartbeats for the same monitor can't both pass
 * `claimTransition` before either records the new state.
 */
internal class HeartbeatDedupe {
    private val lock = Any()
    private val state = HashMap<Int, MonitorStatus>()

    /**
     * Atomic claim: if [status] differs from the prior recorded status
     * for [monitorId], record the new value and return the captured
     * prior state for use with [rollback]; if it's a duplicate, return
     * null. The caller is expected to short-circuit on null
     * (no notification needed).
     */
    fun claimTransition(monitorId: Int, status: MonitorStatus): PrevStatus? = synchronized(lock) {
        val prev = state[monitorId]
        if (prev == status) null
        else {
            state[monitorId] = status
            PrevStatus(prev)
        }
    }

    /**
     * Restore the in-memory map to what [claimTransition] saw, used when
     * a downstream notify or persist call failed. Without rollback the
     * dedupe map would silently swallow a missed transition and the
     * next identical heartbeat would also be skipped — process
     * restart would reseed from disk (which still says the *prior*
     * status) and fire a duplicate notification once anything drifts.
     */
    fun rollback(monitorId: Int, captured: PrevStatus) = synchronized(lock) {
        val prev = captured.value
        if (prev != null) state[monitorId] = prev
        else state.remove(monitorId)
    }

    /** Replace the entire map — used at service start to load disk-persisted state. */
    fun seed(map: Map<Int, MonitorStatus>) = synchronized(lock) {
        state.clear()
        state.putAll(map)
    }

    /** Snapshot for testing / diagnostics. */
    fun snapshot(): Map<Int, MonitorStatus> = synchronized(lock) { HashMap(state) }
}
