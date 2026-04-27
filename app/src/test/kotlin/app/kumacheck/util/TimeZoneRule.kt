package app.kumacheck.util

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.TimeZone

/**
 * Pins the default JVM TimeZone for the duration of a test, restoring the
 * prior value afterward. Use in any test that exercises code reading
 * `TimeZone.getDefault()` directly or via `parseBeatTime` / `parseServerDate`.
 *
 * Without this, test order across classes can leak: `RelativeTimeTest` mutates
 * the default TZ via `TimeZone.setDefault(...)` inside try/finally; if a
 * sibling test in another class reads `getDefault()` and the JVM is reused
 * (Gradle's default), a finally that didn't run (e.g. test timeout, JVM kill)
 * leaves global state poisoned. The rule is unconditional restore.
 *
 * Also resets `setServerTimeZone(null)` so tests starting fresh see the
 * device-local fallback rather than whatever a prior test set.
 */
class TimeZoneRule(
    private val tz: TimeZone = TimeZone.getTimeZone("UTC"),
) : TestRule {
    override fun apply(base: Statement, description: Description?): Statement = object : Statement() {
        override fun evaluate() {
            val savedDefault = TimeZone.getDefault()
            TimeZone.setDefault(tz)
            setServerTimeZone(null)
            try {
                base.evaluate()
            } finally {
                TimeZone.setDefault(savedDefault)
                setServerTimeZone(null)
            }
        }
    }
}
