package app.kumacheck.data.socket

import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.MonitorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q5 / T1 / T4 / K1 / V4 regression coverage for the pure helpers
 * extracted from [KumaSocket] event handlers and [ManageViewModel].
 */
class SocketStateHelpersTest {

    private fun beat(id: Int, statusOrdinal: Int = 1, ts: String = "x") = Heartbeat(
        monitorId = id,
        status = MonitorStatus.from(statusOrdinal),
        time = ts,
        msg = "",
        ping = 0.0,
        important = false,
        timeMs = null,
    )

    // ---- appendBeatToRecent (Q5 / T1) ----

    @Test fun `appendBeatToRecent inserts the first beat for a monitor`() {
        val out = appendBeatToRecent(emptyMap(), beat(1), cap = 5)
        assertEquals(1, out[1]?.size)
    }

    @Test fun `appendBeatToRecent caps oldest-first`() {
        var map = emptyMap<Int, List<Heartbeat>>()
        repeat(7) { i -> map = appendBeatToRecent(map, beat(1, ts = "t$i"), cap = 5) }
        val list = map[1]!!
        assertEquals(5, list.size)
        // Oldest two ("t0", "t1") were dropped.
        assertEquals("t2", list.first().time)
        assertEquals("t6", list.last().time)
    }

    @Test fun `appendBeatToRecent keeps other monitors untouched`() {
        val initial = mapOf(2 to listOf(beat(2, ts = "old")))
        val out = appendBeatToRecent(initial, beat(1, ts = "new"), cap = 5)
        assertEquals(listOf("old"), out[2]?.map { it.time })
        assertEquals(listOf("new"), out[1]?.map { it.time })
    }

    // ---- pruneToLiveMonitorIds (K1) ----

    @Test fun `pruneToLiveMonitorIds keeps only ids in the live set`() {
        val before = mapOf(1 to "a", 2 to "b", 3 to "c")
        val after = pruneToLiveMonitorIds(before, setOf(1, 3))
        assertEquals(setOf(1, 3), after.keys)
        assertEquals("a", after[1])
    }

    @Test fun `pruneToLiveMonitorIds returns empty when no overlap`() {
        val before = mapOf(1 to "a", 2 to "b")
        assertEquals(emptyMap<Int, String>(), pruneToLiveMonitorIds(before, setOf(99)))
    }

    @Test fun `pruneToLiveMonitorIds preserves equal-id input`() {
        val before = mapOf(1 to "a", 2 to "b")
        assertEquals(before, pruneToLiveMonitorIds(before, setOf(1, 2)))
    }

    // ---- tryClaimSlot (V4) ----

    @Test fun `tryClaimSlot adds a fresh id and returns true`() {
        val flow = MutableStateFlow<Set<Int>>(emptySet())
        assertTrue(flow.tryClaimSlot(1))
        assertEquals(setOf(1), flow.value)
    }

    @Test fun `tryClaimSlot returns false on a duplicate without mutating`() {
        val flow = MutableStateFlow<Set<Int>>(setOf(1))
        assertFalse(flow.tryClaimSlot(1))
        assertEquals(setOf(1), flow.value)
    }

    @Test fun `tryClaimSlot is independent per id`() {
        val flow = MutableStateFlow<Set<Int>>(setOf(1))
        assertTrue(flow.tryClaimSlot(2))
        assertEquals(setOf(1, 2), flow.value)
    }
}
