package app.kumacheck.notify.widget

import app.kumacheck.data.model.MonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2 regression coverage. The widget's status-row blob is a hand-rolled
 * encoding (`name<SOH>status<SOH>ping`, rows joined by `\n`). A malformed
 * encode silently corrupts the launcher widget; a malformed decode crashes
 * the widget composer. Neither path was tested before.
 */
class SnapshotWriterTest {

    private fun row(name: String, status: MonitorStatus, ping: Int?) =
        StatusSnapshot.Row(name, status, ping)

    @Test fun `simple round-trip preserves all fields`() {
        val rows = listOf(
            row("api.example.com", MonitorStatus.UP, 42),
            row("db.example.com", MonitorStatus.DOWN, null),
            row("queue.example.com", MonitorStatus.MAINTENANCE, 99),
            row("auth.example.com", MonitorStatus.PENDING, null),
        )
        assertEquals(rows, SnapshotWriter.decodeRows(SnapshotWriter.encodeRows(rows)))
    }

    @Test fun `empty input round-trips`() {
        assertEquals(emptyList<StatusSnapshot.Row>(), SnapshotWriter.decodeRows(""))
        assertEquals(emptyList<StatusSnapshot.Row>(), SnapshotWriter.decodeRows(null))
    }

    @Test fun `monitor name with newline is sanitised, not lost`() {
        // Hostile input: a server-side monitor name with embedded \n.
        // Pre-fix the row would have been split into two corrupt rows.
        val input = listOf(row("a\nb", MonitorStatus.UP, 10))
        val out = SnapshotWriter.decodeRows(SnapshotWriter.encodeRows(input))
        assertEquals(1, out.size)
        // The newline was replaced with a space — the row still decodes,
        // it's just that the displayed name has the control char removed.
        assertEquals("a b", out[0].name)
        assertEquals(MonitorStatus.UP, out[0].status)
        assertEquals(10, out[0].pingMs)
    }

    @Test fun `monitor name with carriage return is sanitised`() {
        val input = listOf(row("x\ry", MonitorStatus.UP, 1))
        val out = SnapshotWriter.decodeRows(SnapshotWriter.encodeRows(input))
        assertEquals(1, out.size)
        assertEquals("x y", out[0].name)
    }

    @Test fun `monitor name with SOH delimiter is sanitised`() {
        // The encoder uses SOH () as the field separator. A name
        // that contains SOH must be sanitised first or decode would split
        // it into the wrong number of fields.
        val input = listOf(row("ab", MonitorStatus.DOWN, 5))
        val out = SnapshotWriter.decodeRows(SnapshotWriter.encodeRows(input))
        assertEquals(1, out.size)
        assertEquals("a b", out[0].name)
        assertEquals(MonitorStatus.DOWN, out[0].status)
    }

    @Test fun `monitor name longer than 64 chars is capped`() {
        val longName = "x".repeat(120)
        val input = listOf(row(longName, MonitorStatus.UP, null))
        val out = SnapshotWriter.decodeRows(SnapshotWriter.encodeRows(input))
        assertEquals(64, out[0].name.length)
    }

    @Test fun `unknown status code decodes to UNKNOWN`() {
        // Forwards-compat: a future MonitorStatus value that the local
        // enum doesn't know about must NOT crash the widget — it just
        // shows as UNKNOWN.
        val malformed = "monitor999"
        val out = SnapshotWriter.decodeRows(malformed)
        assertEquals(1, out.size)
        assertEquals(MonitorStatus.UNKNOWN, out[0].status)
    }

    @Test fun `decode skips lines with too few fields`() {
        // Defensive: a corrupt blob with a partial line shouldn't crash.
        val malformed = "good142\nbad-line-no-delimiters"
        val out = SnapshotWriter.decodeRows(malformed)
        assertEquals(1, out.size)
        assertEquals("good", out[0].name)
    }

    @Test fun `decodeSpark round-trip via encodeSpark equivalent`() {
        // encodeSpark is a comma-join; we mirror it in the test rather
        // than expose the helper so the test stays a pure data-shape
        // assertion.
        val pings = listOf(10, 20, 30, 40)
        val encoded = pings.joinToString(",")
        assertEquals(pings, SnapshotWriter.decodeSpark(encoded))
    }

    @Test fun `decodeSpark on null or blank returns empty`() {
        assertEquals(emptyList<Int>(), SnapshotWriter.decodeSpark(null))
        assertEquals(emptyList<Int>(), SnapshotWriter.decodeSpark(""))
        assertEquals(emptyList<Int>(), SnapshotWriter.decodeSpark("   "))
    }

    @Test fun `decodeSpark drops non-numeric tokens`() {
        // Server hiccup: a corrupt entry mid-stream shouldn't lose the
        // surrounding good values.
        assertEquals(listOf(10, 30), SnapshotWriter.decodeSpark("10,abc,30"))
    }
}
