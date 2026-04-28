package app.kumacheck.notify

import app.kumacheck.data.model.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q5 / T4 / O2 regression coverage for the per-monitor notification
 * dedupe map and its rollback semantics.
 */
class HeartbeatDedupeTest {

    @Test fun `first sighting captures null prior state`() {
        val d = HeartbeatDedupe()
        val captured = d.claimTransition(1, MonitorStatus.DOWN)
        assertEquals(PrevStatus(null), captured)
        assertEquals(MonitorStatus.DOWN, d.snapshot()[1])
    }

    @Test fun `repeated same status returns null (skip)`() {
        val d = HeartbeatDedupe()
        d.claimTransition(1, MonitorStatus.DOWN)
        // Second time around, same status — the caller short-circuits.
        assertNull(d.claimTransition(1, MonitorStatus.DOWN))
        // Map still holds DOWN; no churn.
        assertEquals(MonitorStatus.DOWN, d.snapshot()[1])
    }

    @Test fun `transition captures the prior status`() {
        val d = HeartbeatDedupe()
        d.claimTransition(1, MonitorStatus.DOWN)
        val captured = d.claimTransition(1, MonitorStatus.UP)
        assertEquals(PrevStatus(MonitorStatus.DOWN), captured)
        assertEquals(MonitorStatus.UP, d.snapshot()[1])
    }

    @Test fun `rollback restores prior status (transition path)`() {
        // O2 regression: a notify failure must roll the in-memory map back
        // so the next beat re-tries the transition instead of being deduped
        // against a notification that never reached the user.
        val d = HeartbeatDedupe()
        d.claimTransition(1, MonitorStatus.DOWN)
        val captured = d.claimTransition(1, MonitorStatus.UP) ?: error("expected transition")
        d.rollback(1, captured)
        assertEquals(MonitorStatus.DOWN, d.snapshot()[1])
        // Re-asserting the same UP transition now succeeds because the
        // rollback put DOWN back as the prior state.
        assertEquals(PrevStatus(MonitorStatus.DOWN), d.claimTransition(1, MonitorStatus.UP))
    }

    @Test fun `rollback removes key on first-sighting failure`() {
        // First sighting → captured.value == null. Rollback removes the
        // freshly-inserted key entirely. The next beat for this monitor
        // should be a clean first-sighting again.
        val d = HeartbeatDedupe()
        val captured = d.claimTransition(1, MonitorStatus.DOWN) ?: error("expected first")
        d.rollback(1, captured)
        assertTrue(1 !in d.snapshot())
        assertEquals(PrevStatus(null), d.claimTransition(1, MonitorStatus.DOWN))
    }

    @Test fun `seed replaces the entire map atomically`() {
        // Service start / active-server switch reseeds from disk. The
        // clear+populate has to be atomic against concurrent beat checks
        // — without it, a beat racing through the empty window sees
        // prev=null and would double-notify.
        val d = HeartbeatDedupe()
        d.claimTransition(1, MonitorStatus.UP)
        d.claimTransition(2, MonitorStatus.DOWN)
        d.seed(mapOf(3 to MonitorStatus.UP, 4 to MonitorStatus.MAINTENANCE))
        val snap = d.snapshot()
        assertEquals(setOf(3, 4), snap.keys)
        assertEquals(MonitorStatus.UP, snap[3])
        assertEquals(MonitorStatus.MAINTENANCE, snap[4])
    }

    @Test fun `independent monitors are isolated`() {
        val d = HeartbeatDedupe()
        d.claimTransition(1, MonitorStatus.DOWN)
        d.claimTransition(2, MonitorStatus.UP)
        // Monitor 2's UP doesn't deactivate monitor 1's DOWN — claiming
        // the same status for monitor 1 still skips.
        assertNull(d.claimTransition(1, MonitorStatus.DOWN))
        // But a transition on monitor 2 is captured.
        assertEquals(PrevStatus(MonitorStatus.UP), d.claimTransition(2, MonitorStatus.DOWN))
    }
}
