package app.kumacheck.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class RelativeTimeTest {

    @get:Rule val tzRule = TimeZoneRule()

    private val now = 1_700_000_000_000L

    @Test fun `null or blank input returns null`() {
        assertNull(parseBeatTime(null))
        assertNull(parseBeatTime(""))
        assertNull(parseBeatTime("   "))
    }

    @Test fun `unparseable garbage returns null`() {
        assertNull(parseBeatTime("not a date"))
        assertNull(parseBeatTime("99-99-99"))
    }

    @Test fun `parses standard kuma format`() {
        val ms = parseBeatTime("2024-03-15 10:30:00")
        assertEquals(true, ms != null && ms > 0L)
    }

    @Test fun `parses iso 8601 with Z suffix as UTC regardless of default TZ`() {
        // 2024-03-15T10:30:00Z == 1_710_498_600_000 ms epoch
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Chicago"))
            assertEquals(1_710_498_600_000L, parseBeatTime("2024-03-15T10:30:00Z"))
            assertEquals(1_710_498_600_123L, parseBeatTime("2024-03-15T10:30:00.123Z"))
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test fun `relativeTime under five seconds is now`() {
        assertEquals("now", relativeTime(now - 1_000, now))
        assertEquals("now", relativeTime(now - 4_999, now))
    }

    @Test fun `relativeTime in seconds`() {
        assertEquals("12s", relativeTime(now - 12_000, now))
        assertEquals("59s", relativeTime(now - 59_000, now))
    }

    @Test fun `relativeTime in minutes`() {
        assertEquals("1m", relativeTime(now - 60_000, now))
        assertEquals("4m", relativeTime(now - 4 * 60_000, now))
    }

    @Test fun `relativeTime in hours`() {
        assertEquals("2h", relativeTime(now - 2 * 60 * 60_000L, now))
        assertEquals("23h", relativeTime(now - 23 * 60 * 60_000L, now))
    }

    @Test fun `relativeTime in days`() {
        assertEquals("3d", relativeTime(now - 3 * 24 * 60 * 60_000L, now))
        assertEquals("6d", relativeTime(now - 6 * 24 * 60 * 60_000L, now))
    }

    @Test fun `relativeTime in weeks`() {
        assertEquals("1w", relativeTime(now - 7 * 24 * 60 * 60_000L, now))
        assertEquals("4w", relativeTime(now - 28 * 24 * 60 * 60_000L, now))
    }

    @Test fun `future timestamps return now`() {
        assertEquals("now", relativeTime(now + 60_000, now))
    }
}
