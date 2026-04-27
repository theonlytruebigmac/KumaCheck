package app.kumacheck.ui.overview

import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.util.TimeZoneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OverviewViewModelComputeTest {

    @get:Rule val tzRule = TimeZoneRule()

    private fun monitor(
        id: Int, name: String = "m$id", type: String = "http",
        active: Boolean = true, parent: Int? = null,
    ) = Monitor(
        id = id, name = name, type = type, active = active,
        forceInactive = false, parent = parent, tags = emptyList(),
        hostname = null, url = null, port = null,
    )

    private fun beat(
        mid: Int, status: MonitorStatus, time: String,
        ping: Double? = 50.0, important: Boolean = false, msg: String = "",
    ) = Heartbeat(
        monitorId = mid, status = status, time = time,
        msg = msg, ping = ping, important = important,
    )

    @Test fun `aggregates up down maintenance paused counts correctly`() {
        val monitors = mapOf(
            1 to monitor(1),
            2 to monitor(2),
            3 to monitor(3),
            4 to monitor(4, active = false),
            5 to monitor(5, type = "group"),
        )
        val beats = mapOf(
            1 to beat(1, MonitorStatus.UP, "2024-01-01 10:00:00"),
            2 to beat(2, MonitorStatus.DOWN, "2024-01-01 10:00:00"),
            3 to beat(3, MonitorStatus.MAINTENANCE, "2024-01-01 10:00:00"),
        )
        val s = computeOverviewState(monitors, beats, emptyMap(), emptyMap(),
            emptyMap(), emptySet(), emptyMap())
        assertEquals(4, s.total)        // group filtered out
        assertEquals(1, s.up)
        assertEquals(1, s.down)
        assertEquals(1, s.maintenance)
        assertEquals(1, s.paused)
    }

    @Test fun `tile counts plus pending always sum to total (B14 invariant)`() {
        // Round-2 B14: PENDING / UNKNOWN active monitors used to fall through
        // the bucketing when, leaving up + down + maint + paused < total.
        // Now they go into the new `pending` bucket and the invariant holds.
        val monitors = mapOf(
            1 to monitor(1),                            // pending — no beat
            2 to monitor(2),                            // up
            3 to monitor(3, active = false),            // paused
            4 to monitor(4),                            // down
            5 to monitor(5, type = "group"),            // filtered out of total
        )
        val beats = mapOf(
            2 to beat(2, MonitorStatus.UP, "2024-01-01 10:00:00"),
            4 to beat(4, MonitorStatus.DOWN, "2024-01-01 10:00:00"),
        )
        val s = computeOverviewState(monitors, beats, emptyMap(), emptyMap(),
            emptyMap(), emptySet(), emptyMap())
        assertEquals(4, s.total)
        assertEquals(s.total, s.up + s.down + s.maintenance + s.paused + s.pending)
    }

    @Test fun `avgPingMs averages positive pings only`() {
        val monitors = mapOf(1 to monitor(1), 2 to monitor(2), 3 to monitor(3))
        val beats = mapOf(
            1 to beat(1, MonitorStatus.UP, "2024-01-01 10:00:00", ping = 40.0),
            2 to beat(2, MonitorStatus.UP, "2024-01-01 10:00:00", ping = 60.0),
            3 to beat(3, MonitorStatus.UP, "2024-01-01 10:00:00", ping = -1.0),
        )
        val s = computeOverviewState(monitors, beats, emptyMap(), emptyMap(),
            emptyMap(), emptySet(), emptyMap())
        assertEquals(50, s.avgPingMs)
    }

    @Test fun `aggregate uptime averages active non-paused monitors only`() {
        val monitors = mapOf(
            1 to monitor(1),
            2 to monitor(2),
            3 to monitor(3, active = false),
        )
        val uptime = mapOf(
            1 to mapOf("24" to 0.99),
            2 to mapOf("24" to 0.95),
            3 to mapOf("24" to 0.50),    // paused — must NOT pull average down
        )
        val s = computeOverviewState(monitors, emptyMap(), uptime, emptyMap(),
            emptyMap(), emptySet(), emptyMap())
        assertEquals(0.97, s.uptime24h ?: 0.0, 0.0001)
    }

    @Test fun `pinned set is intersected with current monitors`() {
        val monitors = mapOf(1 to monitor(1), 2 to monitor(2))
        val pinned = setOf(1, 99)        // 99 is a stale id from a deleted monitor
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), pinned, emptyMap())
        assertEquals(setOf(1), s.pinnedIds)
        assertEquals(1, s.pinnedHeartbeats.size)
        assertEquals(1, s.pinnedHeartbeats.first().monitor.id)
    }

    @Test fun `recent incidents are sorted desc and capped at 20`() {
        val monitors = mapOf(1 to monitor(1, name = "API"))
        val beats = (1..25).map { i ->
            beat(1, MonitorStatus.DOWN,
                "2024-01-%02d 10:00:00".format(i),
                important = true, msg = "fail $i")
        }
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), mapOf(1 to beats))
        assertEquals(20, s.recentIncidents.size)
        assertTrue(s.recentIncidents.first().msg!!.endsWith("25"))
    }

    @Test fun `recent incidents skip non-important beats`() {
        val monitors = mapOf(1 to monitor(1))
        val recentBeats = mapOf(1 to listOf(
            beat(1, MonitorStatus.DOWN, "2024-01-01 10:00:00", important = false),
            beat(1, MonitorStatus.UP, "2024-01-01 10:01:00", important = true),
        ))
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats)
        assertEquals(1, s.recentIncidents.size)
        assertEquals(MonitorStatus.UP, s.recentIncidents.first().status)
    }

    @Test fun `recentStatuses caps at 24 per monitor`() {
        val monitors = mapOf(1 to monitor(1))
        val beats30 = (1..30).map { i ->
            beat(1, MonitorStatus.UP, "2024-01-%02d 10:00:00".format(i))
        }
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), mapOf(1 to beats30))
        assertEquals(24, s.recentStatuses[1]?.size)
    }

    @Test fun `incidents24h counts only DOWN beats inside 24h window`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(1 to monitor(1))
        // Build timestamps via java.text.SimpleDateFormat-compatible strings,
        // anchored to absolute epoch ms via known offsets from `now`.
        // We'll use beats whose `time` parses to a value < / >= cutoff.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()

        val cutoff = now - 24L * 60 * 60 * 1000
        val recent = fmt.format(java.util.Date(now - 60_000)) // 1 min ago — IN
        val staleDown = fmt.format(java.util.Date(cutoff - 60_000))   // 24h+1m ago — OUT
        val recentUp = fmt.format(java.util.Date(now - 30_000))   // recent UP — IN window but UP

        val recentBeats = mapOf(1 to listOf(
            beat(1, MonitorStatus.DOWN, recent, important = true),
            beat(1, MonitorStatus.DOWN, staleDown, important = true),
            beat(1, MonitorStatus.UP, recentUp, important = true),
        ))

        val s = computeOverviewState(
            monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now,
        )
        assertEquals(1, s.incidents24h)
    }

    @Test fun `groups are filtered out of allHeartbeats and totals`() {
        val monitors = mapOf(
            1 to monitor(1, type = "group"),
            2 to monitor(2),
        )
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), emptyMap())
        assertEquals(1, s.total)
        assertEquals(1, s.allHeartbeats.size)
        assertEquals(2, s.allHeartbeats.first().monitor.id)
    }

    @Test fun `heatStrip24h emits empty when no beats fall in window`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(1 to monitor(1))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val ancient = fmt.format(java.util.Date(now - 25L * 60 * 60 * 1000))
        val recentBeats = mapOf(1 to listOf(beat(1, MonitorStatus.UP, ancient)))
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now)
        assertTrue("heatStrip24h should be empty: ${s.heatStrip24h}", s.heatStrip24h.isEmpty())
    }

    @Test fun `heatStrip24h has 48 cells with newest beat in last bin`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(1 to monitor(1))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val justNow = fmt.format(java.util.Date(now - 5_000))
        val recentBeats = mapOf(1 to listOf(beat(1, MonitorStatus.UP, justNow)))
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now)
        assertEquals(48, s.heatStrip24h.size)
        assertEquals(MonitorStatus.UP, s.heatStrip24h.last())
        // No other bins populated → UNKNOWN everywhere else
        assertEquals(MonitorStatus.UNKNOWN, s.heatStrip24h.first())
    }

    @Test fun `heatStrip24h takes worst status per bin across monitors`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(1 to monitor(1), 2 to monitor(2))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        // Both beats land in the most recent bin (last 30 minutes).
        val a = fmt.format(java.util.Date(now - 60_000))
        val b = fmt.format(java.util.Date(now - 120_000))
        val recentBeats = mapOf(
            1 to listOf(beat(1, MonitorStatus.UP, a)),
            2 to listOf(beat(2, MonitorStatus.DOWN, b)),
        )
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now)
        // Worst-of-two = DOWN
        assertEquals(MonitorStatus.DOWN, s.heatStrip24h.last())
    }

    @Test fun `heatStrip24h skips paused monitors`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(
            1 to monitor(1),                    // active
            2 to monitor(2, active = false),    // paused — should not affect heat
        )
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val ts = fmt.format(java.util.Date(now - 60_000))
        val recentBeats = mapOf(
            1 to listOf(beat(1, MonitorStatus.UP, ts)),
            2 to listOf(beat(2, MonitorStatus.DOWN, ts)),
        )
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now)
        assertEquals(MonitorStatus.UP, s.heatStrip24h.last())
    }

    @Test fun `heatStrip24h spreads beats across distinct bins`() {
        val now = 1_700_000_000_000L
        val monitors = mapOf(1 to monitor(1))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        // Bin width = 24h / 48 = 30 minutes. Place beats at 0min, 90min, 12h ago.
        val tsRecent = fmt.format(java.util.Date(now - 60_000))           // bin 47
        val ts90 = fmt.format(java.util.Date(now - 90L * 60 * 1000))     // bin 44
        val ts12h = fmt.format(java.util.Date(now - 12L * 60 * 60 * 1000)) // bin 23
        val recentBeats = mapOf(1 to listOf(
            beat(1, MonitorStatus.UP, tsRecent),
            beat(1, MonitorStatus.UP, ts90),
            beat(1, MonitorStatus.DOWN, ts12h),
        ))
        val s = computeOverviewState(monitors, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptySet(), recentBeats, nowMs = now)
        assertEquals(MonitorStatus.UP, s.heatStrip24h[47])
        assertEquals(MonitorStatus.UP, s.heatStrip24h[44])
        assertEquals(MonitorStatus.DOWN, s.heatStrip24h[23])
    }
}
