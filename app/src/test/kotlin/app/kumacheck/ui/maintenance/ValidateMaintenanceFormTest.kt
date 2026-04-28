package app.kumacheck.ui.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V1 + cross-rule regression coverage. The validator gates `save()`; if a
 * bad form sneaks past, `buildMaintenancePayload` silently produces a
 * payload that the server may accept with surprising semantics (the V1
 * case: end-before-start → negative span → 60-minute fallback duration).
 */
class ValidateMaintenanceFormTest {

    private fun base() = MaintenanceEditViewModel.Form(title = "patch")

    @Test fun `valid manual form returns null`() {
        assertNull(validateMaintenanceForm(base()))
    }

    @Test fun `blank title is rejected`() {
        assertEquals(
            "Title is required",
            validateMaintenanceForm(base().copy(title = "   ")),
        )
    }

    // ---- SINGLE ----

    @Test fun `SINGLE without dates is rejected`() {
        val f = base().copy(strategy = MaintenanceEditViewModel.Strategy.SINGLE)
        assertEquals(
            "Start and end dates are required",
            validateMaintenanceForm(f),
        )
    }

    @Test fun `SINGLE with end before start is rejected (V1)`() {
        // V1: pre-fix, the negative span fell through and the payload
        // builder silently set duration to 60 minutes.
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.SINGLE,
            startMillis = 2_000L,
            endMillis = 1_000L,
        )
        assertEquals("End must be after start", validateMaintenanceForm(f))
    }

    @Test fun `SINGLE with end equal to start is rejected (V1)`() {
        // Zero-duration is also nonsense — server would treat it as an
        // immediately-expired window.
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.SINGLE,
            startMillis = 1_000L,
            endMillis = 1_000L,
        )
        assertEquals("End must be after start", validateMaintenanceForm(f))
    }

    @Test fun `SINGLE with end after start passes`() {
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.SINGLE,
            startMillis = 1_000L,
            endMillis = 2_000L,
        )
        assertNull(validateMaintenanceForm(f))
    }

    // ---- RECURRING_WEEKDAY ----

    @Test fun `RECURRING_WEEKDAY with no weekdays is rejected`() {
        val f = base().copy(strategy = MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY)
        assertEquals("Pick at least one weekday", validateMaintenanceForm(f))
    }

    @Test fun `RECURRING_WEEKDAY with at least one weekday passes`() {
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY,
            weekdays = setOf(0),
        )
        assertNull(validateMaintenanceForm(f))
    }

    // ---- RECURRING_DAY_OF_MONTH ----

    @Test fun `RECURRING_DAY_OF_MONTH with no days is rejected`() {
        val f = base().copy(strategy = MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH)
        assertEquals("Pick at least one day of the month", validateMaintenanceForm(f))
    }

    @Test fun `RECURRING_DAY_OF_MONTH with at least one day passes`() {
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH,
            daysOfMonth = setOf(1),
        )
        assertNull(validateMaintenanceForm(f))
    }

    // ---- CRON ----

    @Test fun `CRON with blank expression is rejected`() {
        val f = base().copy(strategy = MaintenanceEditViewModel.Strategy.CRON, cron = "")
        assertEquals("Cron expression is required", validateMaintenanceForm(f))
    }

    @Test fun `CRON with 4 fields is rejected`() {
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.CRON,
            cron = "* * * *",
        )
        assertEquals(
            "Cron must have 5 whitespace-separated fields (minute hour day month weekday)",
            validateMaintenanceForm(f),
        )
    }

    @Test fun `CRON with 5 fields passes`() {
        val f = base().copy(
            strategy = MaintenanceEditViewModel.Strategy.CRON,
            cron = "30 3 * * *",
        )
        assertNull(validateMaintenanceForm(f))
    }

    @Test fun `RECURRING_INTERVAL has no extra constraints`() {
        // Same as MANUAL — server-side defaults handle the day count.
        val f = base().copy(strategy = MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL)
        assertNull(validateMaintenanceForm(f))
    }
}
