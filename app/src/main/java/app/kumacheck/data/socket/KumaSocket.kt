package app.kumacheck.data.socket

import android.util.Log
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.socket.KumaJson.parseHeartbeat
import app.kumacheck.data.socket.KumaJson.parseHeartbeatList
import app.kumacheck.data.socket.KumaJson.parseMonitorList
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Long-lived wrapper around io.socket Java client for one Uptime Kuma server.
 * Owns a single Socket; reconnects are handled by the underlying client.
 */
class KumaSocket {

    /**
     * Snapshot accessor for the *previously* observed Kuma server version
     * (read from prefs, written by KumaCheckApp's serverInfo collector).
     * Set by [setCachedKumaVersionProvider]; used by the `heartbeatList`
     * handler to pick the right `overwrite` default before the current
     * connect's `info` event lands. Default returns null (defaults match
     * Kuma 2.x semantics).
     */
    @Volatile
    private var cachedKumaVersionProvider: () -> String? = { null }

    fun setCachedKumaVersionProvider(provider: () -> String?) {
        cachedKumaVersionProvider = provider
    }

    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, LOGIN_REQUIRED, AUTHENTICATED, ERROR }

    private val _connection = MutableStateFlow(Connection.DISCONNECTED)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private fun setConnection(value: Connection) {
        Log.i(TAG, "connection: ${_connection.value} -> $value")
        _connection.value = value
    }

    private val _monitors = MutableStateFlow<Map<Int, Monitor>>(emptyMap())
    val monitors: StateFlow<Map<Int, Monitor>> = _monitors.asStateFlow()

    /**
     * Raw `monitorList` JSON, kept verbatim so `editMonitor` can round-trip
     * server-side fields (notification settings, headers, auth, etc.) we
     * don't model in the parsed [Monitor] but still need to send back.
     */
    private val _monitorsRaw = MutableStateFlow<Map<Int, JSONObject>>(emptyMap())
    val monitorsRaw: StateFlow<Map<Int, JSONObject>> = _monitorsRaw.asStateFlow()

    /** Most recent heartbeat per monitor id. Drives the live "status dot" UI. */
    private val _latestBeat = MutableStateFlow<Map<Int, Heartbeat>>(emptyMap())
    val latestBeat: StateFlow<Map<Int, Heartbeat>> = _latestBeat.asStateFlow()

    /**
     * Rolling window of recent heartbeats per monitor (~last [MAX_RECENT_BEATS]).
     * Seeded from the `heartbeatList` push the server sends on connect, then
     * extended by every live `heartbeat`. This is what the Overview sparklines
     * and any other "recent trend" UI should read so we don't start blank.
     */
    private val _recentBeats = MutableStateFlow<Map<Int, List<Heartbeat>>>(emptyMap())
    val recentBeats: StateFlow<Map<Int, List<Heartbeat>>> = _recentBeats.asStateFlow()

    /**
     * Per-monitor uptime ratios, keyed by monitor id then by window type:
     *   "1"   -> 24h
     *   "720" -> 30d
     *   "0" / others -> session/lifetime depending on Kuma version
     * Values are 0.0..1.0.
     */
    private val _uptime = MutableStateFlow<Map<Int, Map<String, Double>>>(emptyMap())
    val uptime: StateFlow<Map<Int, Map<String, Double>>> = _uptime.asStateFlow()

    /** Per-monitor average ping in ms. */
    private val _avgPing = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val avgPing: StateFlow<Map<Int, Double>> = _avgPing.asStateFlow()

    data class ServerInfo(
        val primaryBaseURL: String?,
        val version: String?,
        val latestVersion: String?,
        val isContainer: Boolean?,
        val dbType: String?,
        val timezone: String?,
    )

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    /**
     * Status pages keyed by id. Kuma 2.x pushes this once after auth via the
     * `statusPageList` event (Kuma 1.x has no equivalent push and exposes
     * `getStatusPageList` as an ack-based call instead). `null` means we
     * haven't received the push yet.
     */
    private val _statusPages = MutableStateFlow<List<app.kumacheck.data.model.StatusPage>?>(null)
    val statusPages: StateFlow<List<app.kumacheck.data.model.StatusPage>?> = _statusPages.asStateFlow()

    /** Active maintenance windows by id. Each row carries enough to show in UI. */
    data class Maintenance(
        val id: Int,
        val title: String,
        val description: String?,
        val active: Boolean,
        val strategy: String?,
        /** Server-reported lifecycle: "scheduled", "under-maintenance", "ended", "inactive", or null. */
        val status: String? = null,
        /** ISO-ish date strings as the server returns them. */
        val startDate: String? = null,
        val endDate: String? = null,
        /** Maintenance window duration in minutes (recurring strategies). */
        val durationMinutes: Int? = null,
        val timezone: String? = null,
        /** 0..6, Monday=0 in Kuma. Empty for non-recurring. */
        val weekdays: List<Int> = emptyList(),
        /** Days of month (1..31) for `recurring-day-of-month`. Empty otherwise. */
        val daysOfMonth: List<Int> = emptyList(),
        /** Days between repeats for `recurring-interval`. */
        val intervalDay: Int? = null,
        /** Cron expression for `cron` strategy. */
        val cron: String? = null,
    )

    private val _maintenance = MutableStateFlow<Map<Int, Maintenance>>(emptyMap())
    val maintenance: StateFlow<Map<Int, Maintenance>> = _maintenance.asStateFlow()

    /** TLS certificate info for HTTPS monitors. Populated by Kuma's `certInfo` socket event. */
    data class CertInfo(
        val valid: Boolean,
        /** Server-cached value at last `certInfo` push. Used as a fallback by [daysRemainingNow]. */
        val daysRemaining: Int?,
        val validTo: String?,
        /**
         * P5: validTo parsed once on ingest. Kuma typically only re-pushes
         * certInfo on cert refresh (~24h cadence), so a long-running app
         * would otherwise show a stale "10 days" warning that should now
         * read "3 days." [daysRemainingNow] re-derives the figure from
         * `now`, falling back to the server-cached [daysRemaining] when
         * we couldn't parse `validTo`.
         */
        val validToMillis: Long? = null,
    ) {
        fun daysRemainingNow(now: Long = System.currentTimeMillis()): Int? {
            if (validToMillis != null) {
                val diff = validToMillis - now
                return (diff / DAY_MS).toInt().coerceAtLeast(0)
            }
            return daysRemaining
        }

        private companion object {
            const val DAY_MS = 24L * 60 * 60 * 1000
        }
    }

    private val _certInfo = MutableStateFlow<Map<Int, CertInfo>>(emptyMap())
    val certInfo: StateFlow<Map<Int, CertInfo>> = _certInfo.asStateFlow()

    @Volatile
    private var currentUrl: String? = null

    /**
     * The URL the socket is currently configured against (or last was, after
     * disconnect). Read-only view for callers that need to verify the URL
     * hasn't shifted under them between a connect and a subsequent emit
     * (e.g. SplashViewModel comparing against the URL it captured at start).
     */
    val activeUrl: String? get() = currentUrl

    /** Stream of every individual `heartbeat` event for incident detection. */
    private val _beats = MutableSharedFlow<Heartbeat>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val beats: SharedFlow<Heartbeat> = _beats.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Server-pushed monitor deletions. Emits the deleted monitor id whenever
     * the server fires `deleteMonitorFromList` (or our explicit
     * `deleteMonitor` RPC succeeds). Consumers — e.g. [KumaCheckApp] — use
     * this to clean up per-monitor persisted state (pin / mute / ack /
     * incident log) that the socket-level [_monitors] cache prune doesn't
     * reach.
     */
    private val _monitorDeletes = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val monitorDeletes: SharedFlow<Int> = _monitorDeletes.asSharedFlow()

    @Volatile
    private var socket: Socket? = null

    /**
     * Generation counter for the current Socket. Bumped on every
     * connect/reconnect/disconnect so K3's stale-handler guard in
     * [wireEvents] can ignore events that the io.socket dispatcher had
     * already queued for the *previous* incarnation when we tore it down.
     * Read without locking — values are monotonic and listener comparisons
     * are equality, so a torn read can only mean "we're stale" (correct).
     */
    @Volatile
    private var socketGeneration: Int = 0

    /**
     * K4: serialise connect / reconnect / disconnect against each other.
     * `connect` and `reconnect` both call `disconnectInternal` then
     * overwrite `socket`; without this lock, two concurrent callers
     * (pull-to-refresh + switch-active-server, in some hypothetical
     * future) could interleave their disconnect/assign sequences and
     * register handlers on a socket the other call had already swapped
     * out.
     */
    private val socketLifecycleLock = Any()

    /**
     * In-flight ack continuations from [call] / [callPositional]. Tracked so
     * [disconnectInternal] can fail them with IOException instead of leaking
     * the coroutine forever (the underlying ack will never fire after the
     * socket is torn down).
     */
    private val pendingCalls = CopyOnWriteArraySet<CancellableContinuation<JSONObject>>()

    fun connect(serverUrl: String) {
        val s = synchronized(socketLifecycleLock) {
            disconnectInternal(clearState = true)
            setConnection(Connection.CONNECTING)
            currentUrl = serverUrl
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 2_000
                reconnectionDelayMax = 30_000
                timeout = 15_000
                // NW7: bypass IO's per-URL Manager cache. The cache is what
                // accumulates engine.io listeners across reconnects (each
                // connect/disconnect cycle adds another set, leaks linearly with
                // reconnect count). forceNew makes every IO.socket allocate a
                // fresh Manager so the prior one is GC-eligible.
                forceNew = true
            }
            val s = IO.socket(serverUrl.trimEnd('/'), opts)
            socket = s
            val gen = ++socketGeneration
            wireEvents(s, gen)
            s
        }
        // s.connect() is safe to invoke outside the lock — Socket.IO does
        // its own queuing — and keeping the I/O kick-off out of the
        // critical section means a slow DNS lookup can't stall a
        // concurrent disconnect.
        s.connect()
    }

    /**
     * Force a fresh handshake — used by pull-to-refresh. Returns false if no
     * URL known. Keeps the cached monitor/beat state during the brief
     * disconnect window so the UI doesn't flash empty; the server's fresh
     * monitorList replaces it on reconnect.
     */
    fun reconnect(): Boolean {
        val s = synchronized(socketLifecycleLock) {
            val url = currentUrl ?: return false
            disconnectInternal(clearState = false)
            setConnection(Connection.CONNECTING)
            currentUrl = url
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 2_000
                reconnectionDelayMax = 30_000
                timeout = 15_000
                forceNew = true  // see connect() for rationale
            }
            val s = IO.socket(url.trimEnd('/'), opts)
            socket = s
            val gen = ++socketGeneration
            wireEvents(s, gen)
            s
        }
        s.connect()
        return true
    }

    private fun wireEvents(s: Socket, generation: Int) {
        // K3: every listener captures `generation`; if a later
        // connect/reconnect/disconnect has bumped [socketGeneration], we're
        // stale and must not mutate state any more. Without this, an event
        // whose dispatch was queued before disconnectInternal could call
        // s.off() can still land — and the closure would happily mutate
        // _latestBeat / _recentBeats after `socket = null`. Keep the
        // wrapper as a local lambda so each handler stays a single-line
        // s.on(event) { … } at the call site.
        val on: (String, (Array<Any?>) -> Unit) -> Unit = { event, block ->
            s.on(event) { args ->
                // io.socket's Listener gives us Array<Any> (Java Object[]),
                // but our handlers were authored against Array<Any?> via
                // KumaJson — widen here so the existing call sites compile
                // unchanged. The varargs slot can never be null in
                // practice; the cast is just shape-matching.
                @Suppress("UNCHECKED_CAST")
                if (socketGeneration == generation) block(args as Array<Any?>)
            }
        }
        on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "connected: sid=${s.id()}")
            setConnection(Connection.CONNECTED)
        }
        on(Socket.EVENT_DISCONNECT) {
            Log.i(TAG, "disconnected: ${it.firstOrNull()}")
            setConnection(Connection.DISCONNECTED)
        }
        on(Socket.EVENT_CONNECT_ERROR) {
            val msg = it.firstOrNull()?.toString() ?: "connect error"
            Log.w(TAG, "connect error: $msg")
            setConnection(Connection.ERROR)
            _errors.tryEmit(msg)
        }
        on("loginRequired") {
            Log.i(TAG, "loginRequired")
            setConnection(Connection.LOGIN_REQUIRED)
        }
        on("info") {
            val o = it.firstOrNull() as? JSONObject
            if (o != null) {
                val incoming = ServerInfo(
                    primaryBaseURL = o.optString("primaryBaseURL").takeIf { s -> s.isNotEmpty() },
                    version = o.optString("version").takeIf { s -> s.isNotEmpty() },
                    latestVersion = o.optString("latestVersion").takeIf { s -> s.isNotEmpty() },
                    isContainer = if (o.has("isContainer")) o.optBoolean("isContainer") else null,
                    dbType = o.optString("dbType").takeIf { s -> s.isNotEmpty() },
                    timezone = o.optString("serverTimezone").takeIf { s -> s.isNotEmpty() },
                )
                // S3: log only the fields we model. The raw JSONObject can
                // carry whatever a forked/malicious server stuffs into it,
                // and on Android < 12 logcat is readable to other apps.
                Log.i(TAG, "info: version=${incoming.version} dbType=${incoming.dbType} tz=${incoming.timezone}")
                // Merge: prefer non-null fields from new info over previous (post-auth is richer).
                _serverInfo.update { cur ->
                    if (cur == null) incoming else ServerInfo(
                        primaryBaseURL = incoming.primaryBaseURL ?: cur.primaryBaseURL,
                        version = incoming.version ?: cur.version,
                        latestVersion = incoming.latestVersion ?: cur.latestVersion,
                        isContainer = incoming.isContainer ?: cur.isContainer,
                        dbType = incoming.dbType ?: cur.dbType,
                        timezone = incoming.timezone ?: cur.timezone,
                    )
                }
            }
        }
        on("statusPageList") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val out = mutableListOf<app.kumacheck.data.model.StatusPage>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val o = obj.optJSONObject(k) ?: continue
                out.add(
                    app.kumacheck.data.model.StatusPage(
                        id = o.optInt("id"),
                        slug = o.optString("slug"),
                        title = o.optString("title", o.optString("slug")),
                        description = o.optString("description").takeIf { it.isNotEmpty() && it != "null" },
                        icon = o.optString("icon").takeIf { it.isNotEmpty() && it != "null" },
                        published = o.optBoolean("published", true),
                    )
                )
            }
            _statusPages.value = out.sortedBy { it.title.lowercase() }
            Log.i(TAG, "statusPageList: ${out.size} pages")
        }
        on("monitorList") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val parsed = parseMonitorList(obj)
            _monitors.value = parsed
            // Index the raw JSON by id so we can round-trip on editMonitor.
            val raw = HashMap<Int, JSONObject>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val m = obj.optJSONObject(k) ?: continue
                val id = m.optInt("id", -1).takeIf { it >= 0 } ?: continue
                raw[id] = m
            }
            _monitorsRaw.value = raw
            // K1: prune parallel maps keyed by monitor id. `monitorList` is a
            // full replace from the server; without this, deleted monitors'
            // beats / uptime / cert info linger in memory for the lifetime
            // of the process. Per-list caps already bound *each* list's
            // size, but the *number* of monitor keys was unbounded. The
            // pure helper [pruneToLiveMonitorIds] keeps the rule testable.
            val live = parsed.keys
            _latestBeat.update { pruneToLiveMonitorIds(it, live) }
            _recentBeats.update { pruneToLiveMonitorIds(it, live) }
            _uptime.update { pruneToLiveMonitorIds(it, live) }
            _avgPing.update { pruneToLiveMonitorIds(it, live) }
            _certInfo.update { pruneToLiveMonitorIds(it, live) }
            Log.i(TAG, "monitorList: ${parsed.size} monitors")
        }
        // Kuma 2.x patches the local monitor map without re-pushing the full
        // `monitorList`. After an `add` or `editMonitor`, the server emits
        // `updateMonitorIntoList` with a partial `{[id]: monitor}` blob; on
        // `deleteMonitor` it emits `deleteMonitorFromList` with the id alone.
        // Without these handlers the monitor list stays stale until the user
        // pulls to refresh.
        on("updateMonitorIntoList") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val patch = parseMonitorList(obj)
            if (patch.isEmpty()) return@on
            _monitors.update { it + patch }
            // Index the raw JSON for the patched ids so editMonitor's
            // round-trip diff still has the latest baseline.
            val rawPatch = HashMap<Int, JSONObject>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val m = obj.optJSONObject(k) ?: continue
                val id = m.optInt("id", -1).takeIf { it >= 0 } ?: continue
                rawPatch[id] = m
            }
            _monitorsRaw.update { it + rawPatch }
            Log.i(TAG, "updateMonitorIntoList: patched ${patch.keys}")
        }
        on("deleteMonitorFromList") { args ->
            val raw = args.firstOrNull()
            val id = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            } ?: return@on
            // Drop the monitor and every parallel map keyed by id, same
            // way `monitorList` does on full replace (K1).
            _monitors.update { it - id }
            _monitorsRaw.update { it - id }
            _latestBeat.update { it - id }
            _recentBeats.update { it - id }
            _uptime.update { it - id }
            _avgPing.update { it - id }
            _certInfo.update { it - id }
            // Notify subscribers (KumaCheckApp wires prefs.forgetMonitor)
            // so per-monitor persisted state — pin, mute, ack, incident
            // log — doesn't survive the deletion.
            _monitorDeletes.tryEmit(id)
            Log.i(TAG, "deleteMonitorFromList: $id")
        }
        on("heartbeat") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            // O6: surface parse failures to logcat so a malformed payload
            // (new Kuma field shape, server bug) doesn't disappear silently
            // and leave the UI showing stale "last seen X ago" forever.
            val hb = runCatching { parseHeartbeat(obj) }
                .onFailure { Log.w(TAG, "parse heartbeat failed", it) }
                .getOrNull() ?: return@on
            _latestBeat.update { it + (hb.monitorId to hb) }
            _beats.tryEmit(hb)
            // Q5 / T1: pure-helper rolling window so the cap is unit-testable.
            _recentBeats.update { appendBeatToRecent(it, hb, MAX_RECENT_BEATS) }
        }
        on("heartbeatList") { args ->
            // O6: route parse failures to logcat so a malformed batch entry
            // doesn't disappear silently. KumaJson stays JVM-pure (no Log)
            // because it runs under unit-test classloaders where Log is
            // unmocked.
            val pair = parseHeartbeatList(args) { t ->
                Log.w(TAG, "parse heartbeat in heartbeatList failed", t)
            } ?: return@on
            val (mid, beats) = pair
            // Third positional arg: `overwrite` flag. See [resolveOverwrite]
            // for the version-aware default. The cached version (from prefs)
            // is consulted as a fallback for the case where the first
            // heartbeatList lands before the current connect's `info` event
            // (Kuma 1.x) — without that, we'd default to overwrite=true and
            // wipe history every connect.
            val explicitOverwrite = args.getOrNull(2) as? Boolean
            val overwrite = resolveOverwrite(
                explicit = explicitOverwrite,
                liveVersion = _serverInfo.value?.version,
                cachedVersion = cachedKumaVersionProvider(),
            )
            // P2: sort/compare via the parsed timeMs, with the raw string as
            // a stable tiebreak so beats with unparseable timestamps still
            // get a deterministic ordering.
            val sorted = beats.sortedWith(compareBy({ it.timeMs ?: Long.MIN_VALUE }, { it.time }))
            sorted.lastOrNull()?.let { latest ->
                _latestBeat.update { cur ->
                    val existing = cur[mid]
                    val existingMs = existing?.timeMs ?: Long.MIN_VALUE
                    val latestMs = latest.timeMs ?: Long.MIN_VALUE
                    if (existing == null || latestMs > existingMs) cur + (mid to latest)
                    else cur
                }
            }
            _recentBeats.update { cur ->
                val merged = if (overwrite) {
                    sorted
                } else {
                    val existing = cur[mid].orEmpty()
                    // Dedupe by `time` since the server may resend overlapping
                    // entries on flap or reconnect; preserve sort order.
                    (existing + sorted).distinctBy { it.time }
                        .sortedWith(compareBy({ it.timeMs ?: Long.MIN_VALUE }, { it.time }))
                }
                cur + (mid to merged.takeLast(MAX_RECENT_BEATS))
            }
        }
        // uptime is positional: (monitorId, type, value). type is "1"=24h, "720"=30d, etc.
        on("uptime") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val type = args.getOrNull(1)?.toString() ?: return@on
            val v = numToDouble(args.getOrNull(2)) ?: return@on
            _uptime.update { cur ->
                cur + (mid to (cur[mid].orEmpty() + (type to v)))
            }
        }
        // avgPing is positional: (monitorId, valueMs)
        on("avgPing") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val v = numToDouble(args.getOrNull(1)) ?: return@on
            _avgPing.update { it + (mid to v) }
        }
        on("maintenanceList") { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            val map = HashMap<Int, Maintenance>(o.length())
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val m = o.optJSONObject(k) ?: continue
                val id = m.optInt("id", -1).takeIf { it >= 0 } ?: continue
                map[id] = parseMaintenance(m, id)
            }
            _maintenance.value = map
        }
        on("certInfo") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val payload = args.getOrNull(1) ?: return@on
            val obj = when (payload) {
                is JSONObject -> payload
                is String -> runCatching { JSONObject(payload) }.getOrNull()
                else -> null
            } ?: return@on
            val validToStr = obj.optString("validTo").takeIf { it.isNotEmpty() && it != "null" }
            val info = CertInfo(
                valid = obj.optBoolean("valid", false),
                daysRemaining = numToInt(obj.opt("daysRemaining")),
                validTo = validToStr,
                // P5: parse ISO `validTo` once at ingest so the UI can re-derive
                // daysRemaining against `now` instead of trusting a server snapshot
                // that may be 24h stale.
                validToMillis = validToStr?.let { app.kumacheck.util.parseBeatTime(it) },
            )
            _certInfo.update { it + (mid to info) }
        }
    }

    private fun parseMaintenance(m: JSONObject, id: Int): Maintenance {
        fun parseIntArray(key: String): MutableList<Int> {
            val out = mutableListOf<Int>()
            m.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val v = arr.opt(i)) {
                        is Number -> out.add(v.toInt())
                        is String -> v.toIntOrNull()?.let(out::add)
                        else -> {}
                    }
                }
            }
            return out
        }
        fun str(k: String): String? =
            m.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        fun num(k: String): Int? = when {
            !m.has(k) || m.isNull(k) -> null
            else -> when (val v = m.get(k)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
        }
        return Maintenance(
            id = id,
            title = m.optString("title", "Maintenance"),
            description = str("description"),
            active = m.optBoolean("active", false),
            strategy = str("strategy"),
            status = str("status"),
            startDate = str("startDate") ?: str("start_date"),
            endDate = str("endDate") ?: str("end_date"),
            durationMinutes = num("duration"),
            timezone = str("timezone"),
            weekdays = parseIntArray("weekdays"),
            daysOfMonth = parseIntArray("daysOfMonth"),
            intervalDay = num("intervalDay"),
            cron = str("cron"),
        )
    }

    private fun numToInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
    private fun numToDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /**
     * Read up to [MAX_RESPONSE_CHARS] characters from [reader]; throw IOException
     * if the upstream sends more. Defends against a buggy or malicious Kuma server
     * OOM'ing the app via an unbounded response body.
     */
    private fun readCapped(reader: java.io.Reader): String {
        val buf = CharArray(8 * 1024)
        val sb = StringBuilder()
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (sb.length + n > MAX_RESPONSE_CHARS) {
                throw IOException("response exceeds $MAX_RESPONSE_CHARS char cap")
            }
            sb.appendRange(buf, 0, n)
        }
        return sb.toString()
    }

    /**
     * Suspend version of socket.emit with ack. Server reply is one JSONObject.
     * Throws IOException on missing/empty ack, or on no ack within [CALL_TIMEOUT_MS]
     * — without that timeout a server that accepts the emit but never acks
     * would hang the calling coroutine indefinitely (until socket disconnect).
     */
    suspend fun call(event: String, payload: Any? = null): JSONObject =
        withCallTimeout(event) {
            suspendCancellableCoroutine { cont ->
                pendingCalls += cont
                cont.invokeOnCancellation { pendingCalls -= cont }
                val s = socket
                if (s == null) {
                    if (pendingCalls.remove(cont)) cont.resumeWithException(IOException("not connected"))
                    return@suspendCancellableCoroutine
                }
                val ack = Ack { responses ->
                    if (!pendingCalls.remove(cont)) return@Ack
                    val resp = responses.firstOrNull() as? JSONObject
                    if (resp != null) cont.resume(resp)
                    else cont.resumeWithException(IOException("$event: empty ack"))
                }
                try {
                    if (payload != null) s.emit(event, arrayOf(payload), ack)
                    else s.emit(event, emptyArray(), ack)
                } catch (t: Throwable) {
                    if (pendingCalls.remove(cont)) cont.resumeWithException(t)
                }
            }
        }

    /**
     * Variadic emit (positional args). Used for events that the server expects
     * as a tuple, e.g. getMonitorBeats(monitorId, hours).
     */
    suspend fun callPositional(event: String, vararg args: Any?): JSONObject =
        withCallTimeout(event) {
            suspendCancellableCoroutine { cont ->
                pendingCalls += cont
                cont.invokeOnCancellation { pendingCalls -= cont }
                val s = socket
                if (s == null) {
                    if (pendingCalls.remove(cont)) cont.resumeWithException(IOException("not connected"))
                    return@suspendCancellableCoroutine
                }
                val ack = Ack { responses ->
                    if (!pendingCalls.remove(cont)) return@Ack
                    val resp = responses.firstOrNull() as? JSONObject
                    if (resp != null) cont.resume(resp)
                    else cont.resumeWithException(IOException("$event: empty ack"))
                }
                try {
                    @Suppress("UNCHECKED_CAST")
                    s.emit(event, args as Array<Any?>, ack)
                } catch (t: Throwable) {
                    if (pendingCalls.remove(cont)) cont.resumeWithException(t)
                }
            }
        }

    private suspend inline fun withCallTimeout(
        event: String,
        crossinline block: suspend () -> JSONObject,
    ): JSONObject = try {
        withTimeout(CALL_TIMEOUT_MS) { block() }
    } catch (t: TimeoutCancellationException) {
        throw IOException("$event: timed out after ${CALL_TIMEOUT_MS}ms", t)
    }

    /** Pause/resume — server pushes a fresh monitorList after, so UI updates on its own. */
    suspend fun pauseMonitor(id: Int): Boolean =
        callPositional("pauseMonitor", id).optBoolean("ok", false)

    suspend fun resumeMonitor(id: Int): Boolean =
        callPositional("resumeMonitor", id).optBoolean("ok", false)

    /** Pause/resume maintenance window. Server emits a fresh maintenanceList after. */
    suspend fun pauseMaintenance(id: Int): Boolean =
        callPositional("pauseMaintenance", id).optBoolean("ok", false)

    suspend fun resumeMaintenance(id: Int): Boolean =
        callPositional("resumeMaintenance", id).optBoolean("ok", false)

    /**
     * Create a new maintenance window. Returns (newId, error). Caller should
     * follow up with [addMonitorMaintenance] to associate monitors.
     */
    suspend fun addMaintenance(payload: JSONObject): Pair<Int?, String?> {
        val resp = call("addMaintenance", payload)
        if (!resp.optBoolean("ok", false)) {
            return null to resp.optString("msg").takeIf { it.isNotEmpty() }
        }
        val id = resp.optInt("maintenanceID", -1).takeIf { it >= 0 }
            ?: resp.optInt("maintenanceId", -1).takeIf { it >= 0 }
        return id to null
    }

    /** Update an existing maintenance window. Payload must include `id`. */
    suspend fun editMaintenance(payload: JSONObject): Pair<Boolean, String?> {
        val resp = call("editMaintenance", payload)
        val ok = resp.optBoolean("ok", false)
        return ok to (if (ok) null else resp.optString("msg").takeIf { it.isNotEmpty() })
    }

    suspend fun deleteMaintenance(id: Int): Pair<Boolean, String?> {
        val resp = callPositional("deleteMaintenance", id)
        val ok = resp.optBoolean("ok", false)
        return ok to (if (ok) null else resp.optString("msg").takeIf { it.isNotEmpty() })
    }

    /** Replace the set of monitors a maintenance window applies to. */
    suspend fun addMonitorMaintenance(
        maintenanceId: Int,
        monitorIds: List<Int>,
    ): Pair<Boolean, String?> {
        val arr = org.json.JSONArray()
        monitorIds.forEach { arr.put(JSONObject().put("id", it)) }
        val resp = callPositional("addMonitorMaintenance", maintenanceId, arr)
        val ok = resp.optBoolean("ok", false)
        return ok to (if (ok) null else resp.optString("msg").takeIf { it.isNotEmpty() })
    }

    /** Fetch the IDs of monitors currently associated with a maintenance window. */
    suspend fun getMonitorMaintenance(maintenanceId: Int): List<Int> {
        val resp = callPositional("getMonitorMaintenance", maintenanceId)
        if (!resp.optBoolean("ok", false)) return emptyList()
        val arr = resp.optJSONArray("monitors") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1).takeIf { it >= 0 } ?: continue
            out.add(id)
        }
        return out
    }

    /** Persist edits to a monitor. Pass the FULL monitor JSON with desired changes. */
    suspend fun editMonitor(payload: JSONObject): Pair<Boolean, String?> {
        val resp = call("editMonitor", payload)
        val ok = resp.optBoolean("ok", false)
        val msg = if (ok) null else resp.optString("msg").takeIf { it.isNotEmpty() }
        return ok to msg
    }

    /**
     * Create a new monitor. Server emits the `add` event and returns
     * `{ok, msg, monitorID}` on success, or `{ok:false, msg}` on failure.
     * The new id is returned so callers can route into the detail screen.
     */
    suspend fun addMonitor(payload: JSONObject): Triple<Boolean, Int?, String?> {
        // Debug: log the outgoing payload + the server response so a server
        // reject like "cannot read properties of undefined (reading 'every')"
        // can be traced back to the exact JSON shape we sent.
        Log.i(TAG, "addMonitor payload: $payload")
        val resp = call("add", payload)
        Log.i(TAG, "addMonitor response: $resp")
        val ok = resp.optBoolean("ok", false)
        val id = if (resp.has("monitorID") && !resp.isNull("monitorID")) {
            when (val v = resp.opt("monitorID")) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
        } else null
        val msg = resp.optString("msg").takeIf { it.isNotEmpty() }
        return Triple(ok, id, msg)
    }

    suspend fun deleteMonitor(id: Int): Pair<Boolean, String?> {
        val resp = callPositional("deleteMonitor", id)
        val ok = resp.optBoolean("ok", false)
        return ok to (if (ok) null else resp.optString("msg").takeIf { it.isNotEmpty() })
    }

    /**
     * Fetch heartbeat history for one monitor. Returns parsed beats sorted oldest-first.
     * Server returns {"ok": true, "data": [...]} (or {"ok": false, "msg": "..."}).
     */
    suspend fun fetchMonitorBeats(monitorId: Int, hours: Int = 24): List<Heartbeat> {
        val resp = callPositional("getMonitorBeats", monitorId, hours)
        if (!resp.optBoolean("ok", false)) return emptyList()
        val arr = resp.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Heartbeat>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { KumaJson.parseHeartbeat(o) }
                .onFailure { Log.w(TAG, "parse historical heartbeat failed", it) }
                .getOrNull()?.let(out::add)
        }
        return out.sortedWith(compareBy({ it.timeMs ?: Long.MIN_VALUE }, { it.time }))
    }

    /**
     * Status pages: read-only summary. Server returns
     * `{ok:true, statusPageList: [{id, slug, title, ...}, ...]}`.
     */
    suspend fun fetchStatusPageList(): List<app.kumacheck.data.model.StatusPage> {
        val resp = call("getStatusPageList")
        if (!resp.optBoolean("ok", false)) return emptyList()
        val arr = resp.optJSONArray("statusPageList") ?: return emptyList()
        val out = ArrayList<app.kumacheck.data.model.StatusPage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                app.kumacheck.data.model.StatusPage(
                    id = o.optInt("id"),
                    slug = o.optString("slug"),
                    title = o.optString("title", o.optString("slug")),
                    description = o.optString("description").takeIf { it.isNotEmpty() && it != "null" },
                    icon = o.optString("icon").takeIf { it.isNotEmpty() && it != "null" },
                    published = o.optBoolean("published", false),
                )
            )
        }
        return out.sortedBy { it.title.lowercase() }
    }

    /**
     * Fetch one status page's full detail: page metadata, monitor groups, current
     * pinned incident. Returns null only when the server cleanly responds that
     * the slug is unknown (HTTP 404 or response missing `config`); throws
     * [IOException] for any network-level or HTTP error so the caller can
     * distinguish "this slug doesn't exist" from "we couldn't reach the server".
     *
     * Kuma 2.x split this: the socket's `getStatusPage` only returns `{config}`
     * with no groups, while the REST endpoint `/api/status-page/:slug` returns
     * the full `{config, incident, publicGroupList, maintenanceList}` payload.
     * We hit the REST path so groups/monitors actually show up.
     */
    suspend fun fetchStatusPage(slug: String): app.kumacheck.data.model.StatusPageDetail? {
        // Status page slugs are user-controlled (server-pushed but
        // round-tripped through us into a URL path). Reject anything
        // containing path separators, query/fragment markers, or `..` so a
        // weird slug can't path-traverse on the Kuma server, sneak query
        // params past us, or break out of the /api/status-page/ scope.
        if (slug.isEmpty() || slug.length > 128 ||
            slug.any { it == '/' || it == '?' || it == '#' || it.isWhitespace() } ||
            slug.contains("..")) {
            throw IOException("invalid status page slug")
        }
        val base = currentUrl?.trimEnd('/') ?: throw IOException("no server configured")
        val url = "$base/api/status-page/$slug"
        // Wrap the whole IO block in withTimeout (NW4) — HttpURLConnection's
        // `readTimeout` only applies *between* bytes, so a slow-loris server
        // sending 1 byte every 9s could otherwise hold the connection for
        // hours before MAX_RESPONSE_CHARS trips. 20s is a generous wall-clock
        // ceiling; the connect+readBetweenBytes timeouts inside still fire
        // earlier on the more common failure modes.
        val resp = withTimeout(REST_TOTAL_TIMEOUT_MS) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // KS3: OkHttp instead of HttpURLConnection — connection
                // pooling, automatic redirect handling (we still opt out via
                // .followRedirects(false) for parity with the original),
                // and fewer footguns than the HttpURLConnection lifecycle.
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                restHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 404) return@withContext null
                    if (code !in 200..299) throw IOException("HTTP $code")
                    // Captive-portal guard (NW5): if the upstream returned
                    // HTML (Wi-Fi sign-in page) the parse below would throw
                    // a JSONException whose message contains the raw body —
                    // surface a friendlier error instead.
                    val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                    if (contentType.isNotEmpty() && !contentType.contains("json")) {
                        throw IOException("Server returned $contentType (captive portal?)")
                    }
                    // KS1 / KS3: OkHttp parses Content-Type charset for us;
                    // `body.string()` decodes the entire response. The 2 MiB
                    // cap is enforced via `Content-Length` upfront where the
                    // server provides it — Kuma always does — so a hostile
                    // multi-megabyte response is rejected before allocation.
                    val body = response.body ?: throw IOException("empty response body")
                    val len = body.contentLength()
                    if (len > MAX_RESPONSE_BYTES) {
                        throw IOException("response exceeds ${MAX_RESPONSE_BYTES} byte cap")
                    }
                    // Earlier `peek().readUtf8(MAX_RESPONSE_BYTES.toLong())`
                    // call demanded *exactly* that many bytes and threw
                    // EOFException whenever the body was smaller — i.e. on
                    // every real status page, since they're a few KB at most.
                    val text = body.string()
                    if (text.isBlank()) throw IOException("empty response body")
                    JSONObject(text)
                }
            }
        } ?: return null

        val configObj = resp.optJSONObject("config") ?: return null
        val page = app.kumacheck.data.model.StatusPage(
            id = configObj.optInt("id"),
            slug = configObj.optString("slug", slug),
            title = configObj.optString("title", slug),
            description = configObj.optString("description").takeIf { it.isNotEmpty() && it != "null" },
            icon = configObj.optString("icon").takeIf { it.isNotEmpty() && it != "null" },
            published = configObj.optBoolean("published", true),
        )

        // publicGroupList: [{name, monitorList: [{id, name, ...}]}]
        val groupsArr = resp.optJSONArray("publicGroupList")
        val groups = mutableListOf<app.kumacheck.data.model.StatusPageGroup>()
        if (groupsArr != null) {
            for (i in 0 until groupsArr.length()) {
                val g = groupsArr.optJSONObject(i) ?: continue
                val ids = mutableListOf<Int>()
                val mList = g.optJSONArray("monitorList")
                if (mList != null) {
                    for (j in 0 until mList.length()) {
                        val m = mList.optJSONObject(j) ?: continue
                        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: continue
                        ids.add(id)
                    }
                }
                groups.add(
                    app.kumacheck.data.model.StatusPageGroup(
                        name = g.optString("name", "(unnamed group)"),
                        monitorIds = ids,
                    )
                )
            }
        }

        val incidentObj = resp.optJSONObject("incident")
        val incident = if (incidentObj != null && incidentObj.length() > 0) {
            app.kumacheck.data.model.StatusPageIncident(
                id = incidentObj.optInt("id"),
                title = incidentObj.optString("title"),
                content = incidentObj.optString("content"),
                style = app.kumacheck.data.model.IncidentStyle.from(
                    incidentObj.optString("style").takeIf { it.isNotEmpty() && it != "null" },
                ),
                createdDate = incidentObj.optString("createdDate").takeIf { it.isNotEmpty() && it != "null" },
                pin = incidentObj.optBoolean("pin", false),
            )
        } else null

        return app.kumacheck.data.model.StatusPageDetail(
            page = page,
            groups = groups,
            incident = incident,
        )
    }

    /**
     * Post a pinned incident announcement on a status page. Server replies with
     * `{ok, incident}` and the next REST fetch will surface the new incident.
     */
    suspend fun postIncident(
        slug: String,
        title: String,
        content: String,
        style: app.kumacheck.data.model.IncidentStyle = app.kumacheck.data.model.IncidentStyle.WARNING,
    ): Pair<Boolean, String?> {
        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("style", style.wire)
        val resp = runCatching { callPositional("postIncident", slug, payload) }
            .getOrElse { return false to (it.message ?: "network error") }
        val ok = resp.optBoolean("ok", false)
        val msg = resp.optString("msg").takeIf { it.isNotEmpty() }
        return ok to msg
    }

    /** Unpin / clear the active incident on a status page. */
    suspend fun unpinIncident(slug: String): Pair<Boolean, String?> {
        val resp = runCatching { callPositional("unpinIncident", slug) }
            .getOrElse { return false to (it.message ?: "network error") }
        val ok = resp.optBoolean("ok", false)
        val msg = resp.optString("msg").takeIf { it.isNotEmpty() }
        return ok to msg
    }

    fun markAuthenticated() { setConnection(Connection.AUTHENTICATED) }

    fun disconnect() = synchronized(socketLifecycleLock) {
        // K4: synchronize with connect / reconnect so a concurrent caller
        // can't see a half-torn-down state.
        disconnectInternal(clearState = true)
    }

    /**
     * KS2: stop the underlying io.socket reconnect loop without forgetting
     * the active URL — used when the app goes to background AND
     * notifications are disabled (no MonitorService keeping us alive). The
     * monitor / beat caches stay so the UI doesn't flash empty if the user
     * comes back quickly. Idempotent.
     *
     * Calling [resumeReconnection] (or [reconnect] / [connect]) reverses
     * the pause; the active URL stored in [currentUrl] is what
     * [resumeReconnection] reuses.
     */
    fun pauseReconnection(): Boolean = synchronized(socketLifecycleLock) {
        val s = socket ?: return false
        socketGeneration++
        s.off()
        s.disconnect()
        socket = null
        // Fail any in-flight ack waiters; their acks will never arrive now.
        for (cont in pendingCalls.toList()) {
            if (pendingCalls.remove(cont)) cont.resumeWithException(IOException("paused"))
        }
        setConnection(Connection.DISCONNECTED)
        Log.i(TAG, "reconnection paused for $currentUrl")
        true
    }

    /**
     * KS2 counterpart to [pauseReconnection]: re-arm the socket against
     * the previously connected URL. No-op if no URL is known (sign-out,
     * cold start) or if a socket is already live. Returns true if a
     * fresh connect was kicked off.
     */
    fun resumeReconnection(): Boolean {
        val url = synchronized(socketLifecycleLock) {
            if (socket != null) return false
            currentUrl ?: return false
        }
        Log.i(TAG, "reconnection resumed for $url")
        return reconnect()
    }

    private fun disconnectInternal(clearState: Boolean) {
        // K3: invalidate every still-queued listener up front so even an
        // event whose dispatch races us past `s.off()` finds the
        // generation mismatch and no-ops. Bumped before the actual
        // detach so there's no window where listeners look live.
        socketGeneration++
        socket?.let {
            it.off()
            it.disconnect()
        }
        socket = null
        // Fail any in-flight ack waiters; their acks will never arrive now.
        for (cont in pendingCalls.toList()) {
            if (pendingCalls.remove(cont)) cont.resumeWithException(IOException("disconnected"))
        }
        setConnection(Connection.DISCONNECTED)
        if (clearState) {
            _monitors.value = emptyMap()
            _monitorsRaw.value = emptyMap()
            _latestBeat.value = emptyMap()
            _recentBeats.value = emptyMap()
            _uptime.value = emptyMap()
            _avgPing.value = emptyMap()
            _serverInfo.value = null
            _maintenance.value = emptyMap()
            _certInfo.value = emptyMap()
            _statusPages.value = null
        }
    }

    /**
     * KS3: shared OkHttp client for the REST helper. Rebuilt once per
     * [KumaSocket] (singleton effective, since the App holds a single
     * `socket` instance). Configured with the same timeouts the prior
     * HttpURLConnection used; redirects disabled for parity.
     */
    private val restHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofMillis(10_000))
            .readTimeout(java.time.Duration.ofMillis(10_000))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    companion object {
        private const val TAG = "KumaSocket"
        private const val MAX_RECENT_BEATS = 50
        /** Hard cap on REST response body size — ~2 MiB worth of UTF-16 chars. */
        private const val MAX_RESPONSE_CHARS = 1_000_000
        /** KS3: byte cap for OkHttp `peek().readUtf8(n)` — ~2 MiB. */
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        /** Per-emit ack timeout. Anything heavier (e.g. import) needs its own override. */
        private const val CALL_TIMEOUT_MS = 30_000L
        /** Wall-clock cap on a single REST call (NW4). */
        private const val REST_TOTAL_TIMEOUT_MS = 20_000L
    }
}

/**
 * Pure resolver for the `heartbeatList` overwrite flag. Pulled to top-level so
 * unit tests can exercise every branch without spinning up a socket.
 *
 * Decision tree:
 *  - If the server explicitly sent the third arg, honor it.
 *  - Else if `liveVersion` (this connect's `info` event) is known, use that.
 *  - Else if `cachedVersion` (the prior connect's last-seen version, persisted
 *    via [KumaPrefs.setActiveServerKumaVersion]) is known, use that — this is
 *    what closes the Kuma 1.x race where `heartbeatList` arrives before `info`.
 *  - Else if the version is unknown entirely, default to `false`. K2:
 *    Kuma 2.x always sends the explicit `overwrite` boolean, so reaching
 *    this branch means the server *isn't* known to be 2.x — most likely
 *    a Kuma 1.x server whose `info` push hasn't arrived yet. Defaulting
 *    to overwrite would wipe locally seeded history; appending is the
 *    safe choice.
 *  - Else (version is "2.x" or another non-1.x value) default to `true`
 *    (Kuma 2.x convention).
 *
 * Version of "1.x" → false (incremental append, the Kuma 1.x convention).
 * Other known versions → true (full reseed, Kuma 2.x convention).
 * Unknown version → false (conservative; favour append over data loss).
 */
internal fun resolveOverwrite(
    explicit: Boolean?,
    liveVersion: String?,
    cachedVersion: String?,
): Boolean {
    if (explicit != null) return explicit
    val effectiveVersion = liveVersion ?: cachedVersion ?: return false
    return !effectiveVersion.startsWith("1.")
}
