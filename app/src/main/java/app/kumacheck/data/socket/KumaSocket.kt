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
        val daysRemaining: Int?,
        val validTo: String?,
    )

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

    @Volatile
    private var socket: Socket? = null

    /**
     * In-flight ack continuations from [call] / [callPositional]. Tracked so
     * [disconnectInternal] can fail them with IOException instead of leaking
     * the coroutine forever (the underlying ack will never fire after the
     * socket is torn down).
     */
    private val pendingCalls = CopyOnWriteArraySet<CancellableContinuation<JSONObject>>()

    fun connect(serverUrl: String) {
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
        wireEvents(s)
        s.connect()
    }

    /**
     * Force a fresh handshake — used by pull-to-refresh. Returns false if no
     * URL known. Keeps the cached monitor/beat state during the brief
     * disconnect window so the UI doesn't flash empty; the server's fresh
     * monitorList replaces it on reconnect.
     */
    fun reconnect(): Boolean {
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
        wireEvents(s)
        s.connect()
        return true
    }

    private fun wireEvents(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "connected: sid=${s.id()}")
            setConnection(Connection.CONNECTED)
        }
        s.on(Socket.EVENT_DISCONNECT) {
            Log.i(TAG, "disconnected: ${it.firstOrNull()}")
            setConnection(Connection.DISCONNECTED)
        }
        s.on(Socket.EVENT_CONNECT_ERROR) {
            val msg = it.firstOrNull()?.toString() ?: "connect error"
            Log.w(TAG, "connect error: $msg")
            setConnection(Connection.ERROR)
            _errors.tryEmit(msg)
        }
        s.on("loginRequired") {
            Log.i(TAG, "loginRequired")
            setConnection(Connection.LOGIN_REQUIRED)
        }
        s.on("info") {
            val o = it.firstOrNull() as? JSONObject
            Log.i(TAG, "info: $o")
            if (o != null) {
                val incoming = ServerInfo(
                    primaryBaseURL = o.optString("primaryBaseURL").takeIf { s -> s.isNotEmpty() },
                    version = o.optString("version").takeIf { s -> s.isNotEmpty() },
                    latestVersion = o.optString("latestVersion").takeIf { s -> s.isNotEmpty() },
                    isContainer = if (o.has("isContainer")) o.optBoolean("isContainer") else null,
                    dbType = o.optString("dbType").takeIf { s -> s.isNotEmpty() },
                    timezone = o.optString("serverTimezone").takeIf { s -> s.isNotEmpty() },
                )
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
        s.on("statusPageList") { args ->
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
        s.on("monitorList") { args ->
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
            Log.i(TAG, "monitorList: ${parsed.size} monitors")
        }
        s.on("heartbeat") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val hb = runCatching { parseHeartbeat(obj) }.getOrNull() ?: return@on
            _latestBeat.update { it + (hb.monitorId to hb) }
            _beats.tryEmit(hb)
            _recentBeats.update { cur ->
                val rolled = (cur[hb.monitorId].orEmpty() + hb).takeLast(MAX_RECENT_BEATS)
                cur + (hb.monitorId to rolled)
            }
        }
        s.on("heartbeatList") { args ->
            val pair = parseHeartbeatList(args) ?: return@on
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
            val sorted = beats.sortedBy { it.time }
            sorted.lastOrNull()?.let { latest ->
                _latestBeat.update { cur ->
                    val existing = cur[mid]
                    if (existing == null || latest.time > existing.time) cur + (mid to latest)
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
                    (existing + sorted).distinctBy { it.time }.sortedBy { it.time }
                }
                cur + (mid to merged.takeLast(MAX_RECENT_BEATS))
            }
        }
        // uptime is positional: (monitorId, type, value). type is "1"=24h, "720"=30d, etc.
        s.on("uptime") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val type = args.getOrNull(1)?.toString() ?: return@on
            val v = numToDouble(args.getOrNull(2)) ?: return@on
            _uptime.update { cur ->
                cur + (mid to (cur[mid].orEmpty() + (type to v)))
            }
        }
        // avgPing is positional: (monitorId, valueMs)
        s.on("avgPing") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val v = numToDouble(args.getOrNull(1)) ?: return@on
            _avgPing.update { it + (mid to v) }
        }
        s.on("maintenanceList") { args ->
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
        s.on("certInfo") { args ->
            val mid = numToInt(args.getOrNull(0)) ?: return@on
            val payload = args.getOrNull(1) ?: return@on
            val obj = when (payload) {
                is JSONObject -> payload
                is String -> runCatching { JSONObject(payload) }.getOrNull()
                else -> null
            } ?: return@on
            val info = CertInfo(
                valid = obj.optBoolean("valid", false),
                daysRemaining = numToInt(obj.opt("daysRemaining")),
                validTo = obj.optString("validTo").takeIf { it.isNotEmpty() && it != "null" },
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
        val resp = call("add", payload)
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
            runCatching { KumaJson.parseHeartbeat(o) }.getOrNull()?.let(out::add)
        }
        return out.sortedBy { it.time }
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
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = conn.responseCode
                    if (code == 404) return@withContext null
                    if (code !in 200..299) throw IOException("HTTP $code")
                    // Captive-portal guard (NW5): if the upstream returned
                    // HTML (Wi-Fi sign-in page) the parse below would throw
                    // a JSONException whose message contains the raw body —
                    // surface a friendlier error instead.
                    val contentType = conn.contentType?.lowercase().orEmpty()
                    if (contentType.isNotEmpty() && !contentType.contains("json")) {
                        throw IOException("Server returned $contentType (captive portal?)")
                    }
                    val body = conn.inputStream.bufferedReader().use { readCapped(it) }
                    // Empty 200 body (NW6): produce a transport-level IOException
                    // rather than the generic JSONException("Value of type ...").
                    if (body.isBlank()) throw IOException("empty response body")
                    JSONObject(body)
                } finally {
                    conn.disconnect()
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
                style = incidentObj.optString("style").takeIf { it.isNotEmpty() && it != "null" },
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
     * `style` is one of "info" / "warning" / "danger" / "primary" (Bootstrap names).
     */
    suspend fun postIncident(
        slug: String,
        title: String,
        content: String,
        style: String = "warning",
    ): Pair<Boolean, String?> {
        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("style", style)
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

    fun disconnect() = disconnectInternal(clearState = true)

    private fun disconnectInternal(clearState: Boolean) {
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

    companion object {
        private const val TAG = "KumaSocket"
        private const val MAX_RECENT_BEATS = 50
        /** Hard cap on REST response body size — ~2 MiB worth of UTF-16 chars. */
        private const val MAX_RESPONSE_CHARS = 1_000_000
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
 *  - Else default to `true` (Kuma 2.x semantics; matches the pre-fix behavior).
 *
 * Version of "1.x" → false (incremental append, the Kuma 1.x convention).
 * Anything else → true (full reseed, Kuma 2.x convention).
 */
internal fun resolveOverwrite(
    explicit: Boolean?,
    liveVersion: String?,
    cachedVersion: String?,
): Boolean {
    if (explicit != null) return explicit
    val effectiveVersion = liveVersion ?: cachedVersion
    if (effectiveVersion?.startsWith("1.") == true) return false
    return true
}
