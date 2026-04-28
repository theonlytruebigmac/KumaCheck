package app.kumacheck.ui.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/**
 * B2 regression coverage. Pre-fix [parseServerDateImpl] used
 * `runCatching { return fmt.parse(s)!!.time }`, which conflated a real
 * null-from-parse with an exception. The fixed version pulls `.time`
 * outside `runCatching` so a parsed-but-null still falls through to the
 * next pattern instead of being smuggled out as a `NullPointerException`.
 */
class ParseServerDateTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun `null input returns null`() {
        assertNull(parseServerDateImpl(null, utc))
    }

    @Test fun `blank input returns null`() {
        assertNull(parseServerDateImpl("   ", utc))
    }

    @Test fun `empty input returns null`() {
        assertNull(parseServerDateImpl("", utc))
    }

    @Test fun `space-separated pattern parses`() {
        // The default Kuma 1.x maintenance startDate / endDate format.
        val ms = parseServerDateImpl("2024-03-15 10:30:00", utc)
        assertNotNull(ms)
        // 2024-03-15T10:30:00Z = 1710498600000
        assertEquals(1_710_498_600_000L, ms)
    }

    @Test fun `T-separated ISO pattern parses`() {
        val ms = parseServerDateImpl("2024-03-15T10:30:00", utc)
        assertEquals(1_710_498_600_000L, ms)
    }

    @Test fun `minute-precision pattern parses`() {
        // The lossy short form Kuma sometimes emits when seconds are zero.
        val ms = parseServerDateImpl("2024-03-15 10:30", utc)
        assertEquals(1_710_498_600_000L, ms)
    }

    @Test fun `unparseable input returns null`() {
        // Pre-fix this path was reachable via the `!!` swallowing the
        // NullPointerException from `parse(s)` returning null. The fix path
        // returns null cleanly instead. (We don't test field-overflow
        // garbage like "2024-13-99 99:99:99" because SimpleDateFormat is
        // lenient by default and silently rolls overflow into adjacent
        // fields — that's a separate behavioural choice, not B2.)
        assertNull(parseServerDateImpl("not a date", utc))
        assertNull(parseServerDateImpl("hello world", utc))
    }

    @Test fun `non-UTC timezone shifts the parsed instant`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val msUtc = parseServerDateImpl("2024-03-15 10:30:00", utc)!!
        val msNy = parseServerDateImpl("2024-03-15 10:30:00", ny)!!
        // March 15 EDT = UTC-4, so NY-local 10:30 is UTC 14:30 → +4h.
        assertEquals(4 * 60 * 60_000L, msNy - msUtc)
    }
}
