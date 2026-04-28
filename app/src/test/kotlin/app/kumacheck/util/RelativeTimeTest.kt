package app.kumacheck.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class RelativeTimeTest {

    // T5: pin to a non-UTC zone so the "Z-suffix is UTC regardless of
    // default" assertion below is meaningful. Other tests in this class
    // are TZ-independent. The rule's finally is what guarantees
    // restoration on test timeout — the previous in-test try/finally
    // could leak global state across the JVM if killed.
    @get:Rule val tzRule = TimeZoneRule(java.util.TimeZone.getTimeZone("America/Chicago"))

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
        // 2024-03-15T10:30:00Z == 1_710_498_600_000 ms epoch.
        // Default TZ is Chicago via the class-level rule; if the parser
        // honoured it instead of the explicit Z, the result would be off
        // by Chicago's UTC offset.
        assertEquals(1_710_498_600_000L, parseBeatTime("2024-03-15T10:30:00Z"))
        assertEquals(1_710_498_600_123L, parseBeatTime("2024-03-15T10:30:00.123Z"))
    }

    @Test fun `parses naive iso 8601 against serverTimeZone when set`() {
        // T3: pattern "yyyy-MM-dd'T'HH:mm:ss" (no Z, no offset). Kuma 1.x
        // emits this shape and the parser must apply the server-reported
        // timezone, not the device default. Without the serverTimeZone
        // hook, a phone in Chicago reading a UTC server would mis-decode
        // every beat by 5h.
        try {
            setServerTimeZone(java.util.TimeZone.getTimeZone("UTC"))
            assertEquals(1_710_498_600_000L, parseBeatTime("2024-03-15T10:30:00"))
        } finally {
            setServerTimeZone(null)
        }
    }

    @Test fun `parses kuma timestamp with millis`() {
        // T3: pattern "yyyy-MM-dd HH:mm:ss.SSS" — non-UTC, server-tz.
        try {
            setServerTimeZone(java.util.TimeZone.getTimeZone("UTC"))
            assertEquals(1_710_498_600_456L, parseBeatTime("2024-03-15 10:30:00.456"))
        } finally {
            setServerTimeZone(null)
        }
    }

    @Test fun `naive timestamp respects serverTimeZone, not device default`() {
        // T3: cross-check that the server-tz hook actually overrides the
        // class-level Chicago default. Same wire string, different
        // serverTimeZone → different epoch.
        try {
            setServerTimeZone(java.util.TimeZone.getTimeZone("UTC"))
            val utc = parseBeatTime("2024-03-15 10:30:00")!!
            setServerTimeZone(java.util.TimeZone.getTimeZone("America/New_York"))
            val ny = parseBeatTime("2024-03-15 10:30:00")!!
            // NY is UTC-4 in March (after DST), so the same wall-clock
            // string is 4h later in epoch ms when interpreted as NY.
            assertEquals(4 * 60 * 60_000L, ny - utc)
        } finally {
            setServerTimeZone(null)
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
