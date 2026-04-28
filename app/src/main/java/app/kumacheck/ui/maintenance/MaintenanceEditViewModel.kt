package app.kumacheck.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Handles both creating a new maintenance window (when [editingId] is null)
 * and editing an existing one. We support a curated set of strategies —
 * Manual, Single (one-shot date range), and Recurring Weekday — which covers
 * the common cases. The full set (interval / day-of-month / cron) lives in
 * the web UI for now.
 */
class MaintenanceEditViewModel(
    private val socket: KumaSocket,
    private val editingId: Int?,
) : ViewModel() {

    enum class Strategy(val key: String, val label: String) {
        MANUAL("manual", "Manual"),
        SINGLE("single", "Single"),
        RECURRING_WEEKDAY("recurring-weekday", "Weekly"),
        RECURRING_INTERVAL("recurring-interval", "Interval"),
        RECURRING_DAY_OF_MONTH("recurring-day-of-month", "Monthly"),
        CRON("cron", "Cron"),
    }

    data class Form(
        val title: String = "",
        val description: String = "",
        val strategy: Strategy = Strategy.MANUAL,
        val active: Boolean = true,
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val weekdays: Set<Int> = emptySet(), // 0=Mon ... 6=Sun (Kuma convention)
        val recurringStartMinuteOfDay: Int = 0,    // 00:00
        val recurringEndMinuteOfDay: Int = 60,     // 01:00
        val durationMinutes: Int? = null,
        val selectedMonitorIds: Set<Int> = emptySet(),
        // recurring-interval
        val intervalDay: Int = 1,
        // recurring-day-of-month
        val daysOfMonth: Set<Int> = emptySet(), // 1..31
        // cron
        val cron: String = "30 3 * * *",
        val cronDurationMinutes: Int = 60,
    )

    data class UiState(
        val form: Form = Form(),
        val monitorOptions: List<Monitor> = emptyList(),
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null,
        val saved: Boolean = false,
        val isEditing: Boolean = false,
        /**
         * M4: non-null when an existing maintenance window had at least
         * one un-parseable date string on load. Tells the UI to surface
         * "couldn't parse server date — please re-enter" instead of the
         * misleading "Start and end dates are required" the SINGLE
         * validator emits when the field is null.
         */
        val loadDateParseFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(isEditing = editingId != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Snapshot monitor list for the picker (filter out groups).
        // distinctUntilChanged on the derived (id, name) pair list so a
        // pure beat-update push that doesn't change which monitors exist
        // doesn't reset the picker sheet's scroll/selection.
        viewModelScope.launch {
            socket.monitors
                .map { map ->
                    map.values
                        .filter { it.type != "group" }
                        .sortedBy { it.name.lowercase() }
                }
                .distinctUntilChanged { a, b ->
                    a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.name == y.name }
                }
                .collect { options ->
                    _state.update { it.copy(monitorOptions = options) }
                }
        }
        // Hydrate form for edit mode. The maintenance entry may not be in
        // socket.maintenance yet — the maintenanceList push from the server
        // can land after this VM is constructed — so suspend on the flow
        // until the entry appears instead of taking a one-shot snapshot.
        if (editingId != null) {
            _state.update { it.copy(isLoading = true) }
            viewModelScope.launch {
                val m = socket.maintenance
                    .map { it[editingId] }
                    .filterNotNull()
                    .first()
                hydrateFromExisting(m)
                val monitorIds = runCatching { socket.getMonitorMaintenance(editingId) }
                    .getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        form = it.form.copy(selectedMonitorIds = monitorIds.toSet()),
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onTitle(v: String) = _state.update { it.copy(form = it.form.copy(title = v), error = null) }
    fun onDescription(v: String) = _state.update {
        it.copy(form = it.form.copy(description = v), error = null)
    }
    fun onStrategy(s: Strategy) = _state.update { ui ->
        // M2: clear fields that don't apply to the new strategy. The screen
        // already hides them visually, but stale state still emits in the
        // payload (the builder is strategy-aware so the server is fine, but
        // a CRON → SINGLE flip would, before this clear, restore a stale
        // typed cron the moment the user toggled back). Title / description
        // / active / monitor selection survive — those are strategy-agnostic.
        val cleared = when (s) {
            Strategy.MANUAL -> ui.form.copy(
                strategy = s,
                startMillis = null,
                endMillis = null,
                weekdays = emptySet(),
                daysOfMonth = emptySet(),
            )
            Strategy.SINGLE -> ui.form.copy(
                strategy = s,
                weekdays = emptySet(),
                daysOfMonth = emptySet(),
            )
            Strategy.RECURRING_WEEKDAY -> ui.form.copy(
                strategy = s,
                startMillis = null,
                endMillis = null,
                daysOfMonth = emptySet(),
            )
            Strategy.RECURRING_INTERVAL -> ui.form.copy(
                strategy = s,
                startMillis = null,
                endMillis = null,
                weekdays = emptySet(),
                daysOfMonth = emptySet(),
            )
            Strategy.RECURRING_DAY_OF_MONTH -> ui.form.copy(
                strategy = s,
                startMillis = null,
                endMillis = null,
                weekdays = emptySet(),
            )
            Strategy.CRON -> ui.form.copy(
                strategy = s,
                startMillis = null,
                endMillis = null,
                weekdays = emptySet(),
                daysOfMonth = emptySet(),
            )
        }
        ui.copy(form = cleared, error = null)
    }
    fun onActive(b: Boolean) = _state.update { it.copy(form = it.form.copy(active = b)) }
    fun onStartMillis(ms: Long?) = _state.update { it.copy(form = it.form.copy(startMillis = ms)) }
    fun onEndMillis(ms: Long?) = _state.update { it.copy(form = it.form.copy(endMillis = ms)) }
    fun onRecurringStart(minuteOfDay: Int) = _state.update {
        it.copy(form = it.form.copy(recurringStartMinuteOfDay = minuteOfDay))
    }
    fun onRecurringEnd(minuteOfDay: Int) = _state.update {
        it.copy(form = it.form.copy(recurringEndMinuteOfDay = minuteOfDay))
    }
    fun onWeekdayToggle(day: Int) = _state.update {
        val cur = it.form.weekdays.toMutableSet()
        if (day in cur) cur.remove(day) else cur.add(day)
        it.copy(form = it.form.copy(weekdays = cur))
    }
    fun onIntervalDay(value: Int) = _state.update {
        it.copy(form = it.form.copy(intervalDay = value.coerceIn(1, 365)))
    }
    fun onDayOfMonthToggle(day: Int) = _state.update {
        val cur = it.form.daysOfMonth.toMutableSet()
        if (day in cur) cur.remove(day) else cur.add(day)
        it.copy(form = it.form.copy(daysOfMonth = cur))
    }
    fun onCron(v: String) = _state.update { it.copy(form = it.form.copy(cron = v), error = null) }
    fun onCronDuration(value: Int) = _state.update {
        it.copy(form = it.form.copy(cronDurationMinutes = value.coerceAtLeast(1)))
    }
    fun onMonitorToggle(id: Int) = _state.update {
        val cur = it.form.selectedMonitorIds.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        it.copy(form = it.form.copy(selectedMonitorIds = cur))
    }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun save() {
        val cur = _state.value
        if (cur.isSaving || cur.isLoading) return

        val f = cur.form
        validateMaintenanceForm(f)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val payload = buildMaintenancePayload(f)
                val maintenanceId = if (editingId != null) {
                    payload.put("id", editingId)
                    val (ok, msg) = socket.editMaintenance(payload)
                    if (!ok) {
                        _state.update { it.copy(isSaving = false, error = msg ?: "Server rejected the change") }
                        return@launch
                    }
                    editingId
                } else {
                    val (id, msg) = socket.addMaintenance(payload)
                    if (id == null) {
                        _state.update { it.copy(isSaving = false, error = msg ?: "Server rejected the change") }
                        return@launch
                    }
                    id
                }
                val (mok, mmsg) = socket.addMonitorMaintenance(
                    maintenanceId, f.selectedMonitorIds.toList(),
                )
                if (!mok) {
                    _state.update { it.copy(isSaving = false, error = mmsg ?: "Saved, but failed to attach monitors") }
                    return@launch
                }
                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (t: Throwable) {
                _state.update { it.copy(isSaving = false, error = t.message ?: "unknown error") }
            }
        }
    }

    private fun hydrateFromExisting(m: KumaSocket.Maintenance) {
        val strategy = Strategy.entries.firstOrNull { it.key == m.strategy } ?: Strategy.MANUAL
        // Bounds-check server-supplied indices before they reach the form: a
        // corrupt server value of `100` for a weekday would later index off
        // the end of WEEKDAY_NAMES, and a daysOfMonth value outside 1..31
        // would render an invisible chip in the day-grid picker.
        val weekdays = m.weekdays.filter { it in 0..6 }.toSet()
        val daysOfMonth = m.daysOfMonth.filter { it in 1..31 }.toSet()
        val intervalDay = (m.intervalDay ?: 1).coerceIn(1, 365)
        // Maintenance windows are written with the server's `timezoneOption`
        // field; on read we MUST parse with that same tz so the wall-clock
        // doesn't shift when the user's device tz differs. Falls back to
        // device-local if the entry didn't carry a timezone.
        val tz = m.timezone?.takeIf { it.isNotBlank() }
            ?.let { runCatching { TimeZone.getTimeZone(it) }.getOrNull() }
            ?: TimeZone.getDefault()
        // M4: track parse failures explicitly so the UI can distinguish
        // "user hasn't typed yet" from "we tried to load a server value
        // and couldn't read it." The SINGLE-strategy validator emits
        // "Start and end dates are required" when these are null —
        // misleading if the user *had* a date, the parser just couldn't
        // read it. The Edit screen now surfaces a banner pointing at
        // that case.
        val startMs = parseServerDate(m.startDate, tz)
        val endMs = parseServerDate(m.endDate, tz)
        val startFailed = !m.startDate.isNullOrBlank() && startMs == null
        val endFailed = !m.endDate.isNullOrBlank() && endMs == null
        _state.update {
            it.copy(
                form = it.form.copy(
                    title = m.title,
                    description = m.description.orEmpty(),
                    strategy = strategy,
                    active = m.active,
                    startMillis = startMs,
                    endMillis = endMs,
                    weekdays = weekdays,
                    daysOfMonth = daysOfMonth,
                    intervalDay = intervalDay,
                    cron = m.cron ?: it.form.cron,
                    cronDurationMinutes = m.durationMinutes ?: it.form.cronDurationMinutes,
                    durationMinutes = m.durationMinutes,
                ),
                loadDateParseFailed = startFailed || endFailed,
            )
        }
    }

    /**
     * Loose cron-shape check. Doesn't verify field semantics (1-59 in
     * minute, etc.) — that's the server's job — but rejects obvious typos
     * like 4-field expressions before they cause an opaque server error.
     */
    // Member shim that delegates to the top-level helper so the
    // file-scoped function is reachable both from the ViewModel and from
    // [validateMaintenanceForm]. Kept here so the tested rule lives in
    // one place.
    private fun isValidCronShape(cron: String): Boolean = isValidCronShapeImpl(cron)

    private fun parseServerDate(s: String?, tz: TimeZone = TimeZone.getDefault()): Long? =
        parseServerDateImpl(s, tz)

    companion object {
        /** 0=Mon … 6=Sun, matching Kuma's weekdays array convention. */
        val WEEKDAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

/**
 * Cheap shape check for cron expressions. Pre-validates obvious garbage
 * (4-field expressions, empty fields) before they hit the server. Not a
 * full cron grammar — `99 99 99 99 99` passes the shape but the server
 * will reject it. Intentional minimum.
 */
private fun isValidCronShapeImpl(cron: String): Boolean {
    val fields = cron.trim().split(Regex("\\s+"))
    return fields.size == 5 && fields.all { it.isNotEmpty() }
}

/**
 * Parse a server-supplied maintenance datetime string. Walks a small set of
 * Kuma-emitted patterns; returns null if none match. Pulled to file scope so
 * tests can call it without a ViewModel/socket harness — see B2 in the audit.
 */
internal fun parseServerDateImpl(s: String?, tz: TimeZone = TimeZone.getDefault()): Long? {
    if (s.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm",
    )
    for (p in patterns) {
        val fmt = SimpleDateFormat(p, Locale.US)
        fmt.timeZone = tz
        // B2: keep runCatching tight around parse() (locale/format errors are
        // normal as we walk the patterns) but pull `.time` out so a legitimate
        // null-from-parse doesn't get conflated with an exception via `!!`.
        val parsed = runCatching { fmt.parse(s) }.getOrNull() ?: continue
        return parsed.time
    }
    return null
}

/**
 * Pure form validator. Returns null if the form is acceptable to send,
 * else a user-facing error message describing the first failure. Pulled
 * out of the ViewModel so tests can exercise the rules without a fake
 * socket / coroutine harness — the error strings are part of the
 * contract and assertions can match them directly.
 */
internal fun validateMaintenanceForm(f: MaintenanceEditViewModel.Form): String? {
    if (f.title.isBlank()) return "Title is required"
    return when (f.strategy) {
        MaintenanceEditViewModel.Strategy.SINGLE -> when {
            f.startMillis == null || f.endMillis == null ->
                "Start and end dates are required"
            // V1: reject end-before-start. Pre-fix this slipped past
            // and `calcDurationMinutes` silently fell back to 60 mins
            // with a negative span on the payload.
            f.endMillis <= f.startMillis ->
                "End must be after start"
            else -> null
        }
        MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY ->
            if (f.weekdays.isEmpty()) "Pick at least one weekday" else null
        MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH ->
            if (f.daysOfMonth.isEmpty()) "Pick at least one day of the month" else null
        MaintenanceEditViewModel.Strategy.CRON -> when {
            f.cron.isBlank() -> "Cron expression is required"
            !isValidCronShapeImpl(f.cron) ->
                "Cron must have 5 whitespace-separated fields (minute hour day month weekday)"
            else -> null
        }
        MaintenanceEditViewModel.Strategy.MANUAL,
        MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL -> null
    }
}

/**
 * Pure form-to-JSON. Lives at the top level so unit tests can call it without
 * a real socket / coroutine harness. Time-zone bits are injected to keep the
 * tests deterministic across machines.
 */
internal fun buildMaintenancePayload(
    f: MaintenanceEditViewModel.Form,
    tz: TimeZone = TimeZone.getDefault(),
    nowMs: Long = System.currentTimeMillis(),
): JSONObject {
    val p = JSONObject()
    p.put("title", f.title.trim())
    p.put("description", f.description)
    p.put("strategy", f.strategy.key)
    p.put("active", f.active)

    // Defaults — server schema expects all of these present even when
    // the chosen strategy doesn't use them.
    p.put("weekdays", JSONArray(f.weekdays.toList().sorted()))
    p.put("daysOfMonth", JSONArray(f.daysOfMonth.toList().sorted()))
    p.put("intervalDay", f.intervalDay)
    // B4: only seed the placeholder cron for strategies that actually
    // consume one (CRON itself, plus the recurring strategies that may
    // derive a fallback). For MANUAL / SINGLE the field stays empty so
    // we don't ship a fake "30 3 * * *" alongside an unrelated date
    // range. The user-typed cron survives if non-blank.
    val cronValue = when (f.strategy) {
        MaintenanceEditViewModel.Strategy.CRON,
        MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY,
        MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL,
        MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH ->
            f.cron.ifBlank { "30 3 * * *" }
        MaintenanceEditViewModel.Strategy.MANUAL,
        MaintenanceEditViewModel.Strategy.SINGLE -> f.cron
    }
    p.put("cron", cronValue)

    when (f.strategy) {
        MaintenanceEditViewModel.Strategy.SINGLE -> {
            val start = f.startMillis?.let { formatServerDate(it, tz) } ?: ""
            val end = f.endMillis?.let { formatServerDate(it, tz) } ?: ""
            p.put("dateRange", JSONArray(listOf(start, end)))
            p.put("timeRange", JSONArray(emptyList<String>()))
            p.put("duration", calcDurationMinutes(f.startMillis, f.endMillis) ?: 60)
        }
        MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY,
        MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL,
        MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH -> {
            p.put("timeRange", buildTimeRange(
                f.recurringStartMinuteOfDay, f.recurringEndMinuteOfDay,
            ))
            p.put("duration", computeWindowMinutes(f))
            p.put("dateRange", JSONArray(listOf("", "")))
        }
        MaintenanceEditViewModel.Strategy.CRON -> {
            p.put("timeRange", JSONArray(emptyList<String>()))
            p.put("dateRange", JSONArray(listOf("", "")))
            p.put("duration", f.cronDurationMinutes)
        }
        MaintenanceEditViewModel.Strategy.MANUAL -> {
            p.put("timeRange", JSONArray(emptyList<String>()))
            p.put("dateRange", JSONArray(listOf("", "")))
            p.put("duration", 0)
        }
    }

    p.put("timezoneOption", tz.id)
    p.put("timezoneOffset", formatTzOffset(tz, nowMs))
    p.put("timeslotsToStart", 1)
    return p
}

private fun buildTimeRange(startMod: Int, endMod: Int): JSONArray {
    fun obj(mod: Int) = JSONObject()
        .put("hours", (mod / 60).coerceIn(0, 23))
        .put("minutes", (mod % 60).coerceIn(0, 59))
        .put("seconds", 0)
    return JSONArray(listOf(obj(startMod), obj(endMod)))
}

private fun computeWindowMinutes(f: MaintenanceEditViewModel.Form): Int {
    val raw = f.recurringEndMinuteOfDay - f.recurringStartMinuteOfDay
    return if (raw <= 0) raw + 24 * 60 else raw
}

private fun formatServerDate(ms: Long, tz: TimeZone): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    fmt.timeZone = tz
    return fmt.format(Date(ms))
}

private fun calcDurationMinutes(start: Long?, end: Long?): Int? {
    if (start == null || end == null || end <= start) return null
    return ((end - start) / 60_000L).toInt()
}

private fun formatTzOffset(tz: TimeZone, nowMs: Long): String {
    val totalMinutes = tz.getOffset(nowMs) / 60_000
    val sign = if (totalMinutes >= 0) "+" else "-"
    val abs = kotlin.math.abs(totalMinutes)
    return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
}
