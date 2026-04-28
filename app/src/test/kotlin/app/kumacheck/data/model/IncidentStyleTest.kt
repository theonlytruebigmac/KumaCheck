package app.kumacheck.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q6: regression coverage for the `String? → IncidentStyle` parse. Kuma
 * sends raw Bootstrap class names on the wire; an unknown future value
 * (e.g. a new `success` style added by a fork) must NOT crash the UI —
 * it falls back to [IncidentStyle.WARNING] which renders sensibly with
 * the existing accent palette.
 */
class IncidentStyleTest {

    @Test fun `each known wire value round-trips`() {
        IncidentStyle.entries.forEach { style ->
            assertEquals(style, IncidentStyle.from(style.wire))
        }
    }

    @Test fun `lowercased input matches`() {
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from("warning"))
        assertEquals(IncidentStyle.DANGER, IncidentStyle.from("danger"))
    }

    @Test fun `mixed case input matches`() {
        // Defensive — Bootstrap is canonically lowercase but we shouldn't
        // crash on a server that capitalises its CSS class names.
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from("Warning"))
        assertEquals(IncidentStyle.DANGER, IncidentStyle.from("DANGER"))
    }

    @Test fun `unknown value falls back to WARNING`() {
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from("success"))
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from("muted"))
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from(""))
    }

    @Test fun `null input falls back to WARNING`() {
        // Kuma will sometimes omit the field entirely — same fallback.
        assertEquals(IncidentStyle.WARNING, IncidentStyle.from(null))
    }
}
