package app.kumacheck.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private data class BeatPattern(val pattern: String, val isUtc: Boolean)

private val BEAT_TIME_PATTERNS = listOf(
    BeatPattern("yyyy-MM-dd HH:mm:ss.SSS", isUtc = false),
    BeatPattern("yyyy-MM-dd HH:mm:ss", isUtc = false),
    BeatPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", isUtc = true),
    BeatPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", isUtc = true),
    BeatPattern("yyyy-MM-dd'T'HH:mm:ss", isUtc = false),
)

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * Server timezone last reported via Kuma's `info` event, for non-Z-suffixed
 * timestamps. Set by KumaCheckApp's serverInfo collector — read here as a
 * non-blocking volatile so the parser stays a pure function from the caller's
 * perspective. Falls back to device-local when unset.
 *
 * Why this matters: Kuma's `heartbeat`/`getMonitorBeats` payloads use
 * `yyyy-MM-dd HH:mm:ss` in the *server's* configured timezone, not UTC. A
 * phone in EST reading a UTC server would otherwise be off by 5h —
 * `relativeTime` says "5h ago" for a beat that just happened, incident
 * ordering breaks across midnight, the heat strip mis-buckets.
 */
@Volatile
private var serverTimeZone: TimeZone? = null

/**
 * Set the server timezone. Pass null to revert to device-local. Idempotent.
 * Called by `KumaCheckApp` after each `info` event with the server's
 * `timezoneOption`. Tests can also set this directly.
 */
fun setServerTimeZone(tz: TimeZone?) {
    serverTimeZone = tz
}

fun parseBeatTime(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    val nonUtcZone = serverTimeZone ?: TimeZone.getDefault()
    for ((pattern, isUtc) in BEAT_TIME_PATTERNS) {
        runCatching {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.timeZone = if (isUtc) UTC else nonUtcZone
            return fmt.parse(s)?.time
        }
    }
    return null
}

/**
 * Per-thread cached `MMM d` formatter — `relativeTime` is called from hot
 * rendering paths (Sparkline, heat strip, every Compose recomposition of any
 * row that shows a relative timestamp). A fresh `SimpleDateFormat` per call
 * was the prior cost. ThreadLocal handles `SimpleDateFormat`'s non-thread-safety.
 */
private val MONTH_DAY_FORMATTER: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
}

fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - epochMs
    if (diff < 0) return "now"
    val secs = diff / 1_000
    if (secs < 5) return "now"
    if (secs < 60) return "${secs}s"
    val mins = secs / 60
    if (mins < 60) return "${mins}m"
    val hours = mins / 60
    if (hours < 24) return "${hours}h"
    val days = hours / 24
    if (days < 7) return "${days}d"
    val weeks = days / 7
    if (weeks < 5) return "${weeks}w"
    return MONTH_DAY_FORMATTER.get()!!.format(java.util.Date(epochMs))
}
