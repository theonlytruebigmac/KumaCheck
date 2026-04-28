package app.kumacheck.ui.login

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A3 regression coverage. Two visually different inputs that point at the
 * same Kuma server must collapse to one canonical form so prefs.setServer's
 * dedupe logic doesn't silently create two server entries.
 */
class NormalizeServerUrlTest {

    @Test fun `trailing slash stripped`() {
        assertEquals("https://kuma.example", normalizeServerUrlImpl("https://kuma.example/"))
    }

    @Test fun `scheme lowercased`() {
        assertEquals("https://kuma.example", normalizeServerUrlImpl("HTTPS://kuma.example"))
    }

    @Test fun `host lowercased`() {
        assertEquals("https://kuma.example", normalizeServerUrlImpl("https://Kuma.Example"))
    }

    @Test fun `default https port dropped`() {
        assertEquals("https://kuma.example", normalizeServerUrlImpl("https://kuma.example:443"))
    }

    @Test fun `default http port dropped`() {
        assertEquals("http://kuma.example", normalizeServerUrlImpl("http://kuma.example:80"))
    }

    @Test fun `non-default port preserved`() {
        assertEquals("https://kuma.example:8443", normalizeServerUrlImpl("https://kuma.example:8443"))
    }

    @Test fun `subpath preserved without trailing slash`() {
        assertEquals(
            "https://kuma.example/api",
            normalizeServerUrlImpl("https://kuma.example/api/"),
        )
    }

    @Test fun `bare slash path is not stripped`() {
        // Stripping the lone "/" would turn the URI into something URI
        // parsers might re-add later as a different host root, so we
        // leave it.
        assertEquals("https://kuma.example", normalizeServerUrlImpl("https://kuma.example/"))
    }

    @Test fun `idempotent`() {
        val once = normalizeServerUrlImpl("HTTPS://Kuma.Example:443/")
        val twice = normalizeServerUrlImpl(once)
        assertEquals(once, twice)
    }

    @Test fun `lan ip stays intact`() {
        assertEquals(
            "http://192.168.1.20:3001",
            normalizeServerUrlImpl("HTTP://192.168.1.20:3001"),
        )
    }
}
