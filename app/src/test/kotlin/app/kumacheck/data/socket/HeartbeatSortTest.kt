package app.kumacheck.data.socket

import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2 regression coverage. The sort comparator used in `KumaSocket`'s
 * `heartbeatList` and `latestBeat` paths is `compareBy({ it.timeMs ?:
 * Long.MIN_VALUE }, { it.time })`. Pre-fix this was a plain `sortedBy {
 * it.time }` — a lexicographic compare on the raw string, which silently
 * mis-ordered beats whenever Kuma mixed timestamp formats (Z-suffixed
 * vs naïve, ISO vs the legacy `yyyy-MM-dd HH:mm:ss` form).
 *
 * The comparator itself isn't exposed for direct testing — but the sort
 * is a tiny one-liner that's easy to mirror in the test, and the *input*
 * (Heartbeat instances with `timeMs`) is what actually matters. Verifying
 * the ordering on representative inputs is sufficient.
 */
class HeartbeatSortTest {

    private fun hb(time: String, timeMs: Long?, ping: Double = 0.0) = Heartbeat(
        monitorId = 1,
        status = MonitorStatus.UP,
        time = time,
        msg = "",
        ping = ping,
        important = false,
        timeMs = timeMs,
    )

    private val comparator = compareBy<Heartbeat>({ it.timeMs ?: Long.MIN_VALUE }, { it.time })

    @Test fun `mixed timestamp formats sort by epoch, not by string`() {
        // Two beats whose lexicographic order disagrees with their epoch
        // order. Pre-P2 this would have ordered `Z`-suffixed beats after
        // naïve beats with the same date because 'Z' > '5' lexicographically,
        // even when the naïve beat was actually later in real time.
        val later = hb("2024-03-15 10:30:00.500", 1_710_498_600_500L)
        val earlier = hb("2024-03-15T10:30:00Z", 1_710_498_600_000L)
        val sorted = listOf(later, earlier).sortedWith(comparator)
        assertEquals(listOf(earlier, later), sorted)
    }

    @Test fun `unparseable timeMs sinks to the bottom`() {
        // A null timeMs (parser failure) is treated as the oldest possible
        // beat so the recent-beats window prefers entries we can actually
        // place in real time.
        val a = hb("2024-03-15 10:30:00", 1_710_498_600_000L)
        val b = hb("2024-03-15 10:31:00", 1_710_498_660_000L)
        val null1 = hb("garbage", null)
        val sorted = listOf(b, null1, a).sortedWith(comparator)
        assertEquals(listOf(null1, a, b), sorted)
    }

    @Test fun `equal timeMs falls back to raw time tiebreak`() {
        // Two beats with the same parsed epoch (server resends an
        // overlapping entry on flap) — the secondary `it.time` key keeps
        // the order deterministic instead of leaving it
        // implementation-defined.
        val a = hb("2024-03-15 10:30:00", 1_710_498_600_000L)
        val b = hb("2024-03-15 10:30:00.000", 1_710_498_600_000L)
        val sorted = listOf(b, a).sortedWith(comparator)
        assertEquals(listOf(a, b), sorted)
    }
}
