package app.kumacheck.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Shared "current wall-clock" state, bumped every [TICK_INTERVAL_MS]. Screens
 * read it for their relative-time labels ("Updated 2m ago", "Down for 5m")
 * instead of each spinning their own `LaunchedEffect(Unit) { while (true) … }`.
 *
 * Why one shared ticker:
 *   - Multiple stacked screens (Overview + a Detail on top) each used to run
 *     their own ticker concurrently — wasted wakeups, especially on devices
 *     where Compose Choreographer is sensitive to coroutine churn.
 *   - "Updated $time" / "Down for X" labels would silently freeze when the
 *     screen used `remember { System.currentTimeMillis() }` once and never
 *     refreshed.
 *
 * Provided once near the root via [ProvideTickingNow]; consumers read
 * [LocalTickingNow.current] (a Long state — the read recomposes per tick).
 */
private const val TICK_INTERVAL_MS = 30_000L

/**
 * Default value when no [ProvideTickingNow] is present in the composition.
 * Initialized once and **never updated** after class load — `ProvideTickingNow`
 * also bumps it once per tick as a defense, so even a misuse (e.g. a future
 * `@Preview` composable that imports `LocalTickingNow` directly without
 * wrapping) gets a roughly-correct value as long as one provider exists
 * elsewhere in the process. Consumers SHOULD still be inside
 * `ProvideTickingNow` — this fallback is belt-and-suspenders, not a contract.
 */
private val FallbackTickingNow: MutableLongState = mutableLongStateOf(System.currentTimeMillis())

val LocalTickingNow = compositionLocalOf<MutableLongState> { FallbackTickingNow }

/**
 * Wrap content with a single ticker that updates the provided [MutableLongState]
 * every [TICK_INTERVAL_MS]. Place at the root composable so every screen
 * downstream shares the same cadence.
 */
@Composable
fun ProvideTickingNow(content: @Composable () -> Unit) {
    val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_INTERVAL_MS)
            val now = System.currentTimeMillis()
            tick.longValue = now
            // Also bump the process-singleton fallback so any consumer that
            // (incorrectly) reads LocalTickingNow outside this provider still
            // sees fresh values, as long as at least one provider is alive.
            FallbackTickingNow.longValue = now
        }
    }
    CompositionLocalProvider(LocalTickingNow provides tick, content = content)
}
