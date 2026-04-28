@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package app.kumacheck

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import android.util.Log
import app.kumacheck.data.auth.AuthRepository
import app.kumacheck.data.auth.KumaPrefs
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.data.model.IncidentLogEntry
import app.kumacheck.data.model.MonitorState
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.notify.MonitorService
import app.kumacheck.notify.Notifications
import app.kumacheck.notify.widget.SnapshotWriter
import app.kumacheck.notify.widget.StatusSnapshot
import app.kumacheck.util.parseBeatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

class KumaCheckApp : Application() {
    lateinit var prefs: KumaPrefs
        private set
    lateinit var socket: KumaSocket
        private set
    lateinit var auth: AuthRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Mirror of [KumaPrefs.activeServerKumaVersion] for synchronous read by
     * [KumaSocket.cachedKumaVersionProvider] from the io.socket worker thread.
     * @Volatile gives the publication guarantee; updates come from a coroutine
     * collector below.
     */
    @Volatile
    private var cachedKumaVersion: String? = null

    /**
     * Holds the registered NetworkCallback so we can unregister cleanly.
     * Currently process-lifetime — `Application` has no formal teardown
     * hook on Android, so the callback dies with the process today and
     * there is no observed leak. KCA2: kept *plus* the
     * [unregisterNetworkCallback] helper below so any future teardown
     * path (multi-process split, "disable monitoring" toggle that pauses
     * connectivity reactions, instrumented-test cleanup) has a single
     * idempotent call site to invoke instead of re-deriving the
     * `ConnectivityManager` lookup each time.
     */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * Completes once `Notifications.hydrateNonces` has populated the
     * in-memory nonce store from DataStore. MainActivity awaits this before
     * consuming a deep-link nonce so a process-death restore can find the
     * nonce that was minted in the prior incarnation (PD1).
     */
    lateinit var noncesReady: kotlinx.coroutines.Deferred<Unit>
        private set

    /**
     * Non-null when one of the startup migrations
     * ([KumaPrefs.migrateLegacyPerServerKeysIfNeeded] /
     * [KumaPrefs.migrateTokensIfNeeded]) threw. Surfaced in the Settings
     * banner so the user knows their session may not have come through
     * cleanly and re-signing in is the recovery path. Null on a clean run.
     */
    val migrationFailureMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * Completes once both startup prefs migrations have run (or thrown).
     * Promoted to a class field (KCA1) so the per-feature `appScope.launch`
     * blocks below can be extracted into named setup helpers without losing
     * access to the gate. Initialised in [onCreate] before any consumer
     * launches.
     */
    private lateinit var migrationsDone: kotlinx.coroutines.Deferred<Unit>

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        prefs = KumaPrefs(applicationContext)
        socket = KumaSocket()
        auth = AuthRepository(prefs, socket)

        Notifications.ensureChannels(this)

        // KCA1: extract the self-contained setup blocks (connectivity
        // listener, deep-link nonce wiring, version provider) into named
        // helpers. The longer `appScope.launch` blocks that depend on the
        // local `migrationsDone` Deferred stay inline below.
        registerNetworkCallback()
        wireDeepLinkNoncePersistence()
        socket.setCachedKumaVersionProvider { cachedKumaVersion }

        // One-time migrations from the legacy single-server schema. Both are
        // no-ops once nothing is left to migrate.
        //
        // Captured as a class field (KCA1) so every collector below can
        // `await()` it before reading prefs/socket state. Without this,
        // the auto-reauth listener can race a Splash login against a
        // mid-flight migrateTokensIfNeeded `edit { writeServers(p,
        // readServers(p)) }` and the migration's stale-snapshot rewrite
        // can clobber a freshly set token.
        migrationsDone = appScope.async {
            // O5: explicit catch + rethrow on CancellationException so a
            // future scope-teardown propagates correctly. O3: write the
            // failure reason to a Flow so the Settings banner can surface
            // "session migration failed; please sign in again" rather than
            // just logging silently.
            try {
                prefs.migrateLegacyPerServerKeysIfNeeded()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("KumaCheckApp", "legacy per-server migration failed", t)
                migrationFailureMessage.value = "Server migration failed: ${t.message ?: t.javaClass.simpleName}"
            }
            try {
                prefs.migrateTokensIfNeeded()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("KumaCheckApp", "token migration failed", t)
                migrationFailureMessage.value = "Token migration failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        // The MonitorService should run iff the user has notifications enabled
        // AND a session token AND the runtime POST_NOTIFICATIONS permission
        // is granted (Android 13+). Toggling any flips the service on/off.
        //
        // N1: include the runtime permission in the predicate. Without it,
        // a user who previously enabled notifications and then revoked the
        // permission via Settings → App info would still trigger
        // ServiceCompat.startForeground, whose notification itself is
        // gated by POST_NOTIFICATIONS — silently dropped on Android 13+.
        // We re-poll on every process foreground (ON_START) since runtime
        // permission state isn't a Flow.
        //
        // N2: debounce 200 ms to absorb cold-start jitter. DataStore can
        // emit a short transient `false` for one of the two flows before
        // the persisted value comes through, which without the debounce
        // would cycle MonitorService.stop → startMonitorServiceSafely
        // every cold start.
        // `LifecycleRegistry.addObserver` is main-thread-only — `flowOn(Main)`
        // makes the callbackFlow's producer block (and the awaitClose
        // teardown) run on the Main dispatcher even though `appScope` is
        // Default. Without this the lifecycle observer registration crashes
        // on cold start ("Method addObserver must be called on the main
        // thread") and re-crashes every restart.
        val notifPermissionFlow = callbackFlow<Boolean> {
            val obs = object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    trySend(app.kumacheck.notify.Notifications.hasPostPermission(this@KumaCheckApp))
                }
            }
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
            // Initial read — covers the cold-start path before ON_START fires.
            trySend(app.kumacheck.notify.Notifications.hasPostPermission(this@KumaCheckApp))
            awaitClose {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Main)
        appScope.launch {
            migrationsDone.await()
            combine(
                prefs.notificationsEnabled,
                prefs.token,
                notifPermissionFlow,
            ) { enabled, token, perm ->
                enabled && !token.isNullOrBlank() && perm
            }.debounce(200).distinctUntilChanged().collect { shouldRun ->
                if (shouldRun) startMonitorServiceSafely()
                else MonitorService.stop(this@KumaCheckApp)
            }
        }

        // KS2: pause the socket's reconnect loop when the app is fully
        // backgrounded AND notifications are disabled (no MonitorService
        // running). io.socket otherwise retries every 30 s indefinitely
        // — battery drain with zero user benefit. Resume on foreground or
        // when the user re-enables notifications.
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        // Same Main-thread requirement as the notif permission flow above.
        val foregroundFlow = callbackFlow<Boolean> {
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    trySend(true)
                }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    trySend(false)
                }
            }
            processLifecycle.addObserver(observer)
            trySend(
                processLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
            )
            awaitClose { processLifecycle.removeObserver(observer) }
        }.flowOn(kotlinx.coroutines.Dispatchers.Main)
        appScope.launch {
            migrationsDone.await()
            combine(
                foregroundFlow,
                prefs.notificationsEnabled,
                prefs.token,
            ) { foreground, notifsEnabled, token ->
                // Need the socket if any of: an Activity is in foreground,
                // OR the user wants notifications and we have a session
                // (MonitorService is keeping us alive).
                foreground || (notifsEnabled && !token.isNullOrBlank())
            }.distinctUntilChanged().collect { needed ->
                if (needed) socket.resumeReconnection()
                else socket.pauseReconnection()
            }
        }

        // Auto-reauth: if the socket flips to LOGIN_REQUIRED after we've
        // already authenticated once this process, silently re-issue
        // loginByToken with the stored JWT. State machine extracted into
        // [runAutoReauthLoop] so it can be unit-tested without io.socket.
        appScope.launch {
            migrationsDone.await()
            runAutoReauthLoop(
                connection = socket.connection,
                activeAuthOnce = {
                    // Read URL + token in a single prefs snapshot so we can
                    // pass `expectedUrl` to [AuthRepository.loginByToken]
                    // (NW3): if an active-server switch lands between this
                    // read and the emit, the URL guard inside loginByToken
                    // aborts rather than sending the wrong server's JWT.
                    val entry = prefs.activeServerOnce()
                    val token = entry?.token
                    val url = entry?.url?.takeIf { it.isNotBlank() }
                    if (token != null && url != null) token to url else null
                },
                loginByToken = { token, expectedUrl ->
                    Log.i("KumaCheckApp", "auto-reauth via stored token")
                    auth.loginByToken(token, expectedUrl = expectedUrl)
                },
                clearToken = {
                    Log.w("KumaCheckApp", "auto-reauth gave up after $AUTO_REAUTH_FAILURE_LIMIT failures; clearing stored token")
                    prefs.clearToken()
                },
            )
        }

        // Active-server change listener. Watches the *id* (not the URL) so that
        // the user typing into the URL field on the Login screen — which
        // mutates the active server's URL field — does NOT cause reconnects
        // mid-keystroke. Fires only on a real server switch.
        // Mirror the cached Kuma version into [cachedKumaVersion] for the
        // KumaSocket worker-thread read (R2-2). Re-emits on active-server
        // switch via the underlying flow.
        appScope.launch {
            migrationsDone.await()
            prefs.activeServerKumaVersion.collect { v -> cachedKumaVersion = v }
        }

        // Persist each `info`-push version so the next connect can use it
        // before the new info event lands. Also propagate the server's
        // timezone to the global parser (LZ1) — without this, beats from a
        // server in a different tz than the device parse with the wrong
        // offset and "Xh ago" labels lie.
        appScope.launch {
            migrationsDone.await()
            socket.serverInfo.collect { info ->
                val v = info?.version
                if (!v.isNullOrBlank()) {
                    runCatching { prefs.setActiveServerKumaVersion(v) }
                        .onFailure { Log.w("KumaCheckApp", "kuma version persist failed", it) }
                }
                val tzId = info?.timezone?.takeIf { it.isNotBlank() }
                app.kumacheck.util.setServerTimeZone(
                    tzId?.let { runCatching { java.util.TimeZone.getTimeZone(it) }.getOrNull() }
                )
            }
        }

        appScope.launch {
            migrationsDone.await()
            var lastId: Int? = null
            prefs.activeServerId.collect { id ->
                if (id == lastId) return@collect
                if (lastId != null && id != null) {
                    val url = prefs.activeServerOnce()?.url
                    if (!url.isNullOrBlank()) {
                        Log.i("KumaCheckApp", "active server switched to id=$id ($url)")
                        socket.disconnect()
                        socket.connect(url)
                    }
                } else if (lastId != null && id == null) {
                    // Last remaining server was just removed — sever the socket
                    // so we stop heartbeating against a server the user no
                    // longer has saved. The Splash/Login flow will pick up
                    // again on next app entry.
                    Log.i("KumaCheckApp", "active server cleared; disconnecting socket")
                    socket.disconnect()
                }
                lastId = id
            }
        }

        // Incident log: append every important UP/DOWN heartbeat to durable
        // per-server storage, plus rescan recentBeats once after each
        // authenticated connect to import the heartbeatList seed. Dedupe by
        // (monitorId, timestampMs) inside KumaPrefs makes rescans idempotent.
        // When a monitor is deleted server-side, drop all per-monitor
        // persisted state (pin, mute, notification dedupe, ack, incident
        // log entries) so the local cache doesn't keep references to a
        // monitor that no longer exists.
        appScope.launch {
            migrationsDone.await()
            socket.monitorDeletes.collect { id ->
                runCatching { prefs.forgetMonitor(id) }
                    .onFailure { Log.w("KumaCheckApp", "forgetMonitor($id) failed", it) }
            }
        }

        appScope.launch {
            migrationsDone.await()
            socket.beats.collect { hb ->
                if (!hb.important) return@collect
                if (hb.status != MonitorStatus.UP && hb.status != MonitorStatus.DOWN) return@collect
                // Skip appends to the active server's incident log if the
                // user is signed out. Closes the race where logout clears
                // session prefs but a beat already in flight on the
                // SharedFlow lands a moment later — without this guard, the
                // late beat would write to the just-cleared server's log.
                if (prefs.tokenOnce().isNullOrBlank()) return@collect
                val entry = buildIncidentEntry(hb) ?: return@collect
                runCatching { prefs.appendIncident(entry) }
                    .onFailure { Log.w("KumaCheckApp", "incident append failed", it) }
            }
        }
        appScope.launch {
            migrationsDone.await()
            socket.connection.collect { state ->
                if (state != KumaSocket.Connection.AUTHENTICATED) return@collect
                // Give the server's heartbeatList seed a moment to land.
                delay(SEED_IMPORT_DELAY_MS)
                // Same logout-race guard as the live-beats collector above
                // (R2-3 follow-up to round-2 B12): if the user signed out
                // during the delay, don't write seed beats to the cleared
                // server's incident log.
                if (prefs.tokenOnce().isNullOrBlank()) return@collect
                val map = socket.recentBeats.value
                for ((_, beats) in map) {
                    for (hb in beats) {
                        if (!hb.important) continue
                        if (hb.status != MonitorStatus.UP && hb.status != MonitorStatus.DOWN) continue
                        val entry = buildIncidentEntry(hb) ?: continue
                        runCatching { prefs.appendIncident(entry) }
                            .onFailure { Log.w("KumaCheckApp", "incident seed append failed", it) }
                    }
                }
            }
        }

        // Home-screen widget snapshot updater. Runs whenever the process is
        // alive (foreground UI or MonitorService). When neither is running the
        // widget keeps showing the last snapshot until either comes back up.
        appScope.launch {
            migrationsDone.await()
            kotlinx.coroutines.flow.combine(
                socket.monitors,
                socket.latestBeat,
                socket.recentBeats,
            ) { monitors, beats, recent -> Triple(monitors, beats, recent) }
                .debounce(500)
                .collect { (monitors, beats, recent) ->
                    val snapshot = buildSnapshot(monitors, beats, recent)
                    runCatching { SnapshotWriter.push(this@KumaCheckApp, snapshot) }
                        .onFailure { Log.w("KumaCheckApp", "widget update failed", it) }
                }
        }

        // Prune muted/notified/ack-incident sets of ids for monitors that no
        // longer exist on the active server. Otherwise these sets grow forever
        // as the user creates and deletes monitors.
        //
        // Three guards against destroying legitimate state:
        //   1. The snapshot is paired with the *active server id at emission
        //      time*; pruneStaleMonitorIds rejects mismatches (stops the
        //      previous-server's last snapshot from pruning the new server's
        //      keys after a switch).
        //   2. We require two consecutive identical-keys snapshots before
        //      pruning. A single transient partial monitorList push from the
        //      server (mid-edit, mid-reorder, etc.) won't reach this gate.
        //   3. Empty snapshots never prune — they'd wipe everything.
        appScope.launch {
            migrationsDone.await()
            data class PruneTick(val keys: Set<Int>, val serverId: Int?)
            val empty = PruneTick(emptySet(), null)
            kotlinx.coroutines.flow.combine(
                socket.monitors,
                prefs.activeServerId,
            ) { monitors, serverId -> PruneTick(monitors.keys, serverId) }
                .distinctUntilChanged()
                .debounce(2_000)
                .scan(empty to empty) { acc, next -> acc.second to next }
                .collect { (prev, cur) ->
                    if (cur.keys.isEmpty() || cur.serverId == null) return@collect
                    // Steady-state guard: only prune when the same server
                    // emitted the same key set on the previous tick. Filters
                    // out transient partial monitorList pushes during a
                    // server-side reorder/edit.
                    if (prev.keys != cur.keys || prev.serverId != cur.serverId) return@collect
                    runCatching { prefs.pruneStaleMonitorIds(cur.keys, cur.serverId) }
                        .onFailure { Log.w("KumaCheckApp", "monitor-id prune failed", it) }
                }
        }
    }

    private fun buildIncidentEntry(hb: app.kumacheck.data.model.Heartbeat): IncidentLogEntry? {
        val timeMs = hb.timeMs ?: parseBeatTime(hb.time) ?: return null
        val name = socket.monitors.value[hb.monitorId]?.name ?: "Monitor ${hb.monitorId}"
        return IncidentLogEntry(
            monitorId = hb.monitorId,
            monitorName = name,
            status = hb.status,
            timestampMs = timeMs,
            msg = hb.msg,
        )
    }

    /**
     * Start [MonitorService] without crashing if the system blocks the FGS
     * launch. Today the only writers of `notificationsEnabled` and `token`
     * are foreground UI flows, so this is purely defensive — but if a future
     * path ever flips them from a background context, Android 12+ would throw
     * [ForegroundServiceStartNotAllowedException]. The next time the prefs
     * change while the app is foreground, the collector will retry.
     *
     * Also called from [MainActivity.onStart] to recover from the Android 15
     * `dataSync` 6h cap when the user reopens the app after the system stopped
     * the service.
     */
    internal fun startMonitorServiceSafely() {
        try {
            MonitorService.start(this)
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException) {
                Log.w("KumaCheckApp", "FGS start blocked from background; deferring")
            } else {
                throw e
            }
        }
    }

    private fun buildSnapshot(
        monitors: Map<Int, app.kumacheck.data.model.Monitor>,
        beats: Map<Int, app.kumacheck.data.model.Heartbeat>,
        recent: Map<Int, List<app.kumacheck.data.model.Heartbeat>>,
    ): StatusSnapshot {
        val states = monitors.values
            .filter { it.type != "group" }
            .map { MonitorState(it, beats[it.id]) }

        var up = 0; var down = 0; var maint = 0; var paused = 0
        for (s in states) {
            when {
                !s.monitor.active || s.monitor.forceInactive -> paused++
                s.status == app.kumacheck.data.model.MonitorStatus.UP -> up++
                s.status == app.kumacheck.data.model.MonitorStatus.DOWN -> down++
                s.status == app.kumacheck.data.model.MonitorStatus.MAINTENANCE -> maint++
            }
        }
        // Cap rows at 10 to keep the widget state bounded.
        val rows = states.take(MAX_WIDGET_ROWS).map { st ->
            StatusSnapshot.Row(
                name = st.monitor.name,
                status = st.status,
                pingMs = st.lastHeartbeat?.ping?.takeIf { it >= 0 }?.toInt(),
            )
        }
        // Cross-monitor average ping per index, oldest → newest, capped at SPARK_LEN.
        // For each index from the tail, average the available pings; positions where
        // a monitor has no beat fall through (we just average the present ones).
        val activeMonitorIds = states
            .filter { it.monitor.active && !it.monitor.forceInactive }
            .map { it.monitor.id }
        val sparkPings = run {
            val series = activeMonitorIds.mapNotNull { id ->
                recent[id]
                    ?.takeLast(SPARK_LEN)
                    ?.mapNotNull { it.ping?.takeIf { p -> p >= 0 }?.toInt() }
                    ?.takeIf { it.isNotEmpty() }
            }
            if (series.isEmpty()) emptyList()
            else {
                val n = SPARK_LEN
                (0 until n).map { idxFromEnd ->
                    // For each spark position (oldest at 0), average across monitors
                    // whose tail-aligned series has a value at that depth.
                    val depth = n - 1 - idxFromEnd
                    val vals = series.mapNotNull { s ->
                        val idx = s.size - 1 - depth
                        if (idx >= 0) s[idx] else null
                    }
                    if (vals.isEmpty()) 0 else vals.average().toInt()
                }
            }
        }

        return StatusSnapshot(
            total = states.size,
            up = up, down = down, maintenance = maint, paused = paused,
            timestampMs = System.currentTimeMillis(),
            rows = rows,
            sparkPings = sparkPings,
        )
    }

    /**
     * O4: install an uncaught-exception handler that appends the crash to
     * `filesDir/crash.log` (capped at [CRASH_LOG_MAX_BYTES]) before
     * delegating to the previous handler. With no Crashlytics in the
     * build, this is the only post-mortem trail outside of `adb logcat`.
     *
     * The handler MUST be best-effort: any exception inside it gets
     * swallowed silently, since throwing from an uncaught-exception
     * handler is its own undefined-behaviour territory.
     */
    /**
     * Register a connectivity callback (NW1). Wi-Fi → cellular handovers
     * (and vice versa) silently kill the socket's underlying TCP transport
     * on the old interface; io.socket only notices on the next ping
     * (~25s later) and waits another reconnectionDelay before retrying.
     * Forcing a reconnect on transport flip cuts that gap to ~0.
     */
    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // Only reconnect once we've authenticated at least once
                    // — pre-auth reconnect attempts are handled by the login
                    // flow and would clobber an in-flight Splash handshake.
                    if (socket.connection.value == KumaSocket.Connection.AUTHENTICATED ||
                        socket.connection.value == KumaSocket.Connection.LOGIN_REQUIRED ||
                        socket.connection.value == KumaSocket.Connection.ERROR ||
                        socket.connection.value == KumaSocket.Connection.DISCONNECTED) {
                        Log.i("KumaCheckApp", "network available; reconnecting socket")
                        socket.reconnect()
                    }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure { Log.w("KumaCheckApp", "NetworkCallback register failed", it) }
    }

    /**
     * Wire deep-link nonce persistence (PD1). Hot reads/writes stay
     * in-memory; the disk mirror only matters when the OS kills the app
     * between notification tap and Activity composition.
     *
     * O5: explicit `catch (t: Throwable)` paths re-throw
     * `CancellationException` so a scope cancel (which doesn't happen
     * today on `appScope`, but might in a future teardown path)
     * propagates correctly instead of getting silently swallowed.
     */
    private fun wireDeepLinkNoncePersistence() {
        Notifications.setNoncePersistence(
            add = { entry ->
                appScope.launch {
                    try {
                        prefs.addDeepLinkNonce(entry)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w("KumaCheckApp", "nonce persist (add) failed", t)
                    }
                }
            },
            remove = { entries ->
                appScope.launch {
                    try {
                        prefs.removeDeepLinkNonces(entries)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w("KumaCheckApp", "nonce persist (remove) failed", t)
                    }
                }
            },
        )
        noncesReady = appScope.async {
            try {
                Notifications.hydrateNonces(prefs.deepLinkNoncesOnce())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("KumaCheckApp", "nonce hydrate failed", t)
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = java.io.File(filesDir, "crash.log")
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                pw.println("---- ${java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US,
                ).format(java.util.Date())} thread=${thread.name} ----")
                throwable.printStackTrace(pw)
                pw.flush()
                val entry = sw.toString()
                // Append; rotate by truncate-from-front when the file
                // exceeds the cap so a crash loop doesn't fill the disk.
                val existing = if (log.exists()) log.readText() else ""
                val combined = existing + entry
                val capped = if (combined.length > CRASH_LOG_MAX_BYTES) {
                    combined.takeLast(CRASH_LOG_MAX_BYTES)
                } else combined
                log.writeText(capped)
            } catch (_: Throwable) {
                // best-effort
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * KCA2: idempotent teardown for the connectivity callback registered in
     * [onCreate]. Currently unused — `Application` has no formal teardown
     * hook — but kept as the single call site so the moment a feature
     * needs to disable connectivity reactions (multi-process split,
     * "pause monitoring" toggle, instrumented-test reset) the path is
     * trivially correct.
     */
    @Suppress("unused")
    internal fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(cb)
        }.onFailure { Log.w("KumaCheckApp", "NetworkCallback unregister failed", it) }
        networkCallback = null
    }

    private companion object {
        const val MAX_WIDGET_ROWS = 10
        const val SPARK_LEN = 20
        const val SEED_IMPORT_DELAY_MS = 2_000L
        const val CRASH_LOG_MAX_BYTES = 64 * 1024
    }
}
