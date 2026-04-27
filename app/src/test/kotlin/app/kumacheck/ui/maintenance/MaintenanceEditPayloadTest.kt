package app.kumacheck.ui.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MaintenanceEditPayloadTest {

    private val utc = TimeZone.getTimeZone("UTC")
    // Pin nowMs into UTC's no-DST regime so formatTzOffset stays stable.
    private val nowMs = 1_700_000_000_000L

    private fun form(
        title: String = "Routine",
        description: String = "",
        strategy: MaintenanceEditViewModel.Strategy = MaintenanceEditViewModel.Strategy.MANUAL,
        active: Boolean = true,
        startMillis: Long? = null,
        endMillis: Long? = null,
        weekdays: Set<Int> = emptySet(),
        recurringStartMinuteOfDay: Int = 0,
        recurringEndMinuteOfDay: Int = 60,
        intervalDay: Int = 1,
        daysOfMonth: Set<Int> = emptySet(),
        cron: String = "30 3 * * *",
        cronDurationMinutes: Int = 60,
    ) = MaintenanceEditViewModel.Form(
        title = title, description = description, strategy = strategy,
        active = active, startMillis = startMillis, endMillis = endMillis,
        weekdays = weekdays, recurringStartMinuteOfDay = recurringStartMinuteOfDay,
        recurringEndMinuteOfDay = recurringEndMinuteOfDay, durationMinutes = null,
        selectedMonitorIds = emptySet(), intervalDay = intervalDay,
        daysOfMonth = daysOfMonth, cron = cron, cronDurationMinutes = cronDurationMinutes,
    )

    @Test fun `manual strategy emits empty time and date ranges with zero duration`() {
        val p = buildMaintenancePayload(form(), tz = utc, nowMs = nowMs)
        assertEquals("manual", p.getString("strategy"))
        assertEquals(0, p.getJSONArray("timeRange").length())
        val dr = p.getJSONArray("dateRange")
        assertEquals(2, dr.length())
        assertEquals("", dr.getString(0))
        assertEquals("", dr.getString(1))
        assertEquals(0, p.getInt("duration"))
    }

    @Test fun `single strategy formats date range and computes duration in minutes`() {
        // start 2023-11-14 22:13:20 UTC, end +90m → 2023-11-14 23:43:20 UTC
        val start = nowMs
        val end = nowMs + 90 * 60_000L
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.SINGLE,
                 startMillis = start, endMillis = end),
            tz = utc, nowMs = nowMs,
        )
        assertEquals("single", p.getString("strategy"))
        val dr = p.getJSONArray("dateRange")
        assertEquals("2023-11-14 22:13:20", dr.getString(0))
        assertEquals("2023-11-14 23:43:20", dr.getString(1))
        assertEquals(90, p.getInt("duration"))
    }

    @Test fun `single strategy missing dates emits empty strings and 60-minute fallback duration`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.SINGLE),
            tz = utc, nowMs = nowMs,
        )
        val dr = p.getJSONArray("dateRange")
        assertEquals("", dr.getString(0))
        assertEquals("", dr.getString(1))
        assertEquals(60, p.getInt("duration"))
    }

    @Test fun `recurring weekday emits time range objects and sorted weekdays`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY,
                 weekdays = setOf(4, 0, 2),
                 recurringStartMinuteOfDay = 22 * 60 + 30, // 22:30
                 recurringEndMinuteOfDay = 23 * 60 + 45),  // 23:45
            tz = utc, nowMs = nowMs,
        )
        assertEquals("recurring-weekday", p.getString("strategy"))
        val tr = p.getJSONArray("timeRange")
        assertEquals(2, tr.length())
        assertEquals(22, tr.getJSONObject(0).getInt("hours"))
        assertEquals(30, tr.getJSONObject(0).getInt("minutes"))
        assertEquals(23, tr.getJSONObject(1).getInt("hours"))
        assertEquals(45, tr.getJSONObject(1).getInt("minutes"))
        val wd = p.getJSONArray("weekdays")
        assertEquals(0, wd.getInt(0))
        assertEquals(2, wd.getInt(1))
        assertEquals(4, wd.getInt(2))
        // 75-minute window
        assertEquals(75, p.getInt("duration"))
    }

    @Test fun `recurring window wraps midnight when end is before start`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL,
                 recurringStartMinuteOfDay = 23 * 60, // 23:00
                 recurringEndMinuteOfDay = 1 * 60),   // 01:00
            tz = utc, nowMs = nowMs,
        )
        // (1*60 - 23*60) <= 0 → +1440 → 120 minutes
        assertEquals(120, p.getInt("duration"))
    }

    @Test fun `recurring day-of-month sorts the days array`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH,
                 daysOfMonth = setOf(15, 1, 28)),
            tz = utc, nowMs = nowMs,
        )
        val dom = p.getJSONArray("daysOfMonth")
        assertEquals(1, dom.getInt(0))
        assertEquals(15, dom.getInt(1))
        assertEquals(28, dom.getInt(2))
    }

    @Test fun `cron strategy uses cronDurationMinutes for duration`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.CRON,
                 cron = "0 4 * * *", cronDurationMinutes = 30),
            tz = utc, nowMs = nowMs,
        )
        assertEquals("cron", p.getString("strategy"))
        assertEquals("0 4 * * *", p.getString("cron"))
        assertEquals(30, p.getInt("duration"))
        assertEquals(0, p.getJSONArray("timeRange").length())
    }

    @Test fun `cron strategy falls back to default expression when blank`() {
        val p = buildMaintenancePayload(
            form(strategy = MaintenanceEditViewModel.Strategy.CRON, cron = ""),
            tz = utc, nowMs = nowMs,
        )
        assertEquals("30 3 * * *", p.getString("cron"))
    }

    @Test fun `title is trimmed and description preserved verbatim`() {
        val p = buildMaintenancePayload(
            form(title = "  Spaces  ", description = "  trailing  "),
            tz = utc, nowMs = nowMs,
        )
        assertEquals("Spaces", p.getString("title"))
        assertEquals("  trailing  ", p.getString("description"))
    }

    @Test fun `timezone offset emits sign-prefixed HH-MM`() {
        val p = buildMaintenancePayload(form(), tz = utc, nowMs = nowMs)
        assertEquals("UTC", p.getString("timezoneOption"))
        assertEquals("+00:00", p.getString("timezoneOffset"))

        val ist = TimeZone.getTimeZone("Asia/Kolkata") // +05:30
        val p2 = buildMaintenancePayload(form(), tz = ist, nowMs = nowMs)
        assertEquals("+05:30", p2.getString("timezoneOffset"))

        val pst = TimeZone.getTimeZone("Etc/GMT+8") // -08:00 (no DST)
        val p3 = buildMaintenancePayload(form(), tz = pst, nowMs = nowMs)
        assertEquals("-08:00", p3.getString("timezoneOffset"))
    }

    @Test fun `payload always carries timeslotsToStart equal to 1`() {
        val p = buildMaintenancePayload(form(), tz = utc, nowMs = nowMs)
        assertEquals(1, p.getInt("timeslotsToStart"))
    }

    @Test fun `active flag round-trips`() {
        val on = buildMaintenancePayload(form(active = true), tz = utc, nowMs = nowMs)
        val off = buildMaintenancePayload(form(active = false), tz = utc, nowMs = nowMs)
        assertTrue(on.getBoolean("active"))
        assertTrue(!off.getBoolean("active"))
    }

    @Test fun `every payload includes the schema-required keys`() {
        val p = buildMaintenancePayload(form(), tz = utc, nowMs = nowMs)
        for (k in listOf(
            "title", "description", "strategy", "active", "weekdays",
            "daysOfMonth", "intervalDay", "cron", "dateRange", "timeRange",
            "duration", "timezoneOption", "timezoneOffset", "timeslotsToStart",
        )) {
            assertNotNull("missing key: $k", p.opt(k))
        }
    }
}
