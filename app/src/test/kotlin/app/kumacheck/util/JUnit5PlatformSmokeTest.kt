package app.kumacheck.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test as JupiterTest

/**
 * CI3 smoke test: confirms the JUnit 5 Jupiter engine is wired correctly
 * alongside the Vintage engine that runs the existing JUnit 4 suite. New
 * tests can be written against `org.junit.jupiter.api.Test` (aliased here
 * to avoid shadowing the JUnit 4 import any neighbouring test happens to
 * use); both will run under `:app:testDebugUnitTest`.
 */
class JUnit5PlatformSmokeTest {

    @JupiterTest fun jupiter_assertion_works() {
        assertEquals(4, 2 + 2, "JUnit 5 Jupiter assertion should pass")
    }

    @JupiterTest fun jupiter_failure_message_argument_is_third() {
        // Jupiter swaps the message-argument position vs JUnit 4
        // (expected, actual, message) — sanity check so a future
        // migration of the JUnit 4 tests catches the swap.
        assertEquals("hello", "he" + "llo", "string concat should match")
    }
}
