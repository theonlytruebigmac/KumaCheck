package app.kumacheck.data.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kumacheck.data.model.IncidentLogEntry
import app.kumacheck.data.model.MonitorStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Uptime Kuma server the user has signed into. Persisted in DataStore
 * as a JSON-encoded array under [`SERVERS_JSON`]; lookup goes through the
 * active id.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun fromString(v: String?): ThemeMode = when (v) {
            "LIGHT" -> LIGHT
            "DARK" -> DARK
            else -> SYSTEM
        }
    }
}

data class ServerEntry(
    val id: Int,
    val url: String,
    val username: String?,
    /**
     * Decrypted plaintext token, or null if the server has no token stored or
     * the persisted envelope can't currently be decrypted (e.g. AndroidKeyStore
     * temporarily unavailable). Callers treat null as "no usable session."
     */
    val token: String?,
    /**
     * Internal: the raw on-disk value for `token` as it was last read from
     * `SERVERS_JSON`. Held only so [KumaPrefs.writeServers] can preserve an
     * undecryptable envelope across an unrelated pref write — without this,
     * a notification toggle while the keystore is momentarily flaky would
     * blank the user's session permanently. Not serialized.
     */
    internal val rawToken: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("username", username ?: JSONObject.NULL)
        .put("token", token ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): ServerEntry? {
            val id = o.optInt("id", -1).takeIf { it >= 0 } ?: return null
            val url = o.optString("url").takeIf { it.isNotEmpty() } ?: return null
            return ServerEntry(
                id = id,
                url = url,
                username = o.optString("username").takeIf { it.isNotEmpty() && it != "null" },
                token = o.optString("token").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }
}

private val Context.dataStore by preferencesDataStore(name = "kuma_prefs")

class KumaPrefs(
    private val ctx: Context,
    private val crypto: TokenCrypto = TokenCrypto(),
) {

    // --- Legacy single-server keys (still read for migration) ---
    private val LEGACY_SERVER_URL = stringPreferencesKey("server_url")
    private val LEGACY_TOKEN = stringPreferencesKey("jwt_token")
    private val LEGACY_USERNAME = stringPreferencesKey("username")

    // --- Multi-server registry ---
    private val SERVERS_JSON = stringPreferencesKey("servers_json")
    /**
     * If [SERVERS_JSON] ever fails to parse at the top level (corrupt write,
     * byte-flip, partial flush), we stash the raw value here BEFORE any
     * subsequent write can overwrite it with `[]`. Recovery is manual but the
     * data isn't gone forever.
     */
    private val SERVERS_JSON_BACKUP = stringPreferencesKey("servers_json_backup")
    private val ACTIVE_SERVER_ID = intPreferencesKey("active_server_id")

    // --- App-global prefs ---
    private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    private val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
    private val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
    private val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    /**
     * **Legacy.** Round-1 stored a global "any token plaintext" boolean here.
     * Round-2 derives the active-server-scoped equivalent fresh from
     * `SERVERS_JSON` (see [tokensStoredPlaintext]); we no longer write this
     * key. Kept declared so we can `remove(...)` it on the next list write
     * to keep the prefs file tidy.
     */
    private val TOKENS_STORED_PLAINTEXT_LEGACY = booleanPreferencesKey("tokens_stored_plaintext")

    // --- Per-server prefs ---
    // Pinned + muted monitor ids are scoped to the active server so switching
    // between two Kuma instances doesn't intersect their monitor namespaces.
    // Quiet hours intentionally stay app-global — silencing windows are about
    // the user's day, not which server is active.
    private val LEGACY_PINNED_MONITOR_IDS = stringSetPreferencesKey("pinned_monitor_ids")
    private val LEGACY_MUTED_MONITOR_IDS = stringSetPreferencesKey("muted_monitor_ids")
    private fun pinnedKeyFor(serverId: Int) = stringSetPreferencesKey("pinned_monitor_ids_$serverId")
    private fun mutedKeyFor(serverId: Int) = stringSetPreferencesKey("muted_monitor_ids_$serverId")
    /** Acknowledged incidents — each entry is `"<monitorId>_<startTimestampMs>"`. */
    private fun ackIncidentKeyFor(serverId: Int) = stringSetPreferencesKey("ack_incidents_$serverId")
    /**
     * Last-notified-status per monitor for the foreground service's notification
     * dedupe map. Each entry encodes `"<monitorId>:<statusName>"`. Persisted so a
     * service restart doesn't re-fire the same incident, and scoped per-server so
     * switching servers doesn't carry stale dedupe decisions across.
     */
    private fun notifiedStatusKeyFor(serverId: Int) = stringSetPreferencesKey("notified_status_$serverId")
    /** JSON-array log of historical incidents, capped at [INCIDENT_LOG_MAX] entries, per server. */
    private fun incidentLogKeyFor(serverId: Int) = stringPreferencesKey("incident_log_$serverId")
    /**
     * Last-seen Kuma server version reported via the `info` event, cached so
     * KumaSocket can pick the right `heartbeatList` overwrite default on a
     * fresh connect *before* the new `info` event lands. Without this, the
     * first `heartbeatList` push of every connect against a Kuma 1.x server
     * (where `info` may arrive after the first beat list) would default to
     * overwrite=true and wipe history.
     */
    private fun kumaVersionKeyFor(serverId: Int) = stringPreferencesKey("kuma_version_$serverId")
    /**
     * Persisted store for notification deep-link nonces (PD1). The in-memory
     * store in `Notifications.kt` is wiped on process death, which silently
     * dropped legitimate deep-links when the OS killed the app between
     * notification tap and Activity composition. DataStore is app-private —
     * other apps can't read it — so the nonce's unguessability property
     * holds the same as the in-memory case.
     *
     * Format: `nonce:monitorId:mintedAtMs` per entry; same TTL/cap as
     * in-memory.
     */
    private val DEEP_LINK_NONCES = stringSetPreferencesKey("deep_link_nonces")

    private fun <T> readKey(key: Preferences.Key<T>): Flow<T?> =
        ctx.dataStore.data.map { it[key] }

    /**
     * All saved servers. Built lazily — if the registry is empty but legacy
     * single-server fields are populated, an implicit `id=1` entry is
     * synthesized so the rest of the code sees the same shape regardless of
     * upgrade path.
     */
    val servers: Flow<List<ServerEntry>> = ctx.dataStore.data.map { p ->
        readServers(p)
    }
    val activeServerId: Flow<Int?> = ctx.dataStore.data.map { p -> resolveActiveId(p) }
    val activeServer: Flow<ServerEntry?> = combine(servers, activeServerId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    val serverUrl: Flow<String?> = activeServer.map { it?.url }
    val token: Flow<String?> = activeServer.map { it?.token }
    val username: Flow<String?> = activeServer.map { it?.username }
    val notificationsEnabled: Flow<Boolean> =
        ctx.dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: false }
    @OptIn(ExperimentalCoroutinesApi::class)
    val pinnedMonitorIds: Flow<Set<Int>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else ctx.dataStore.data.map { p ->
            // First read after migration: fall back to the legacy global key
            // so a user who had pins under the single-server schema keeps them
            // on whichever server is now active. The next write drops the
            // legacy key (in setPinnedMonitorIds).
            val src = p[pinnedKeyFor(id)] ?: p[LEGACY_PINNED_MONITOR_IDS]
            (src ?: emptySet()).mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val mutedMonitorIds: Flow<Set<Int>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else ctx.dataStore.data.map { p ->
            val src = p[mutedKeyFor(id)] ?: p[LEGACY_MUTED_MONITOR_IDS]
            (src ?: emptySet()).mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val acknowledgedIncidents: Flow<Set<String>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else ctx.dataStore.data.map { p -> p[ackIncidentKeyFor(id)] ?: emptySet() }
    }
    /**
     * Raw `"<monitorId>:<statusName>"` entries for the dedupe map. Re-emits
     * with the new server's snapshot whenever the active server switches, so
     * a consumer keeps a per-server view automatically.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val notifiedStatusEntries: Flow<Set<String>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else ctx.dataStore.data.map { p -> p[notifiedStatusKeyFor(id)] ?: emptySet() }
    }
    /**
     * Persisted incident log for the active server, newest-first. Sourced
     * from every observed `important=true` heartbeat; survives reconnects,
     * process death, and server switches (per-server scope).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val incidentLog: Flow<List<IncidentLogEntry>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else ctx.dataStore.data.map { p -> decodeIncidentLog(p[incidentLogKeyFor(id)]) }
    }
    val quietHoursEnabled: Flow<Boolean> =
        ctx.dataStore.data.map { it[QUIET_HOURS_ENABLED] ?: false }
    /** Quiet-hours window start as minute-of-day (0..1439). Default 22:00 = 1320. */
    val quietHoursStart: Flow<Int> =
        ctx.dataStore.data.map { it[QUIET_HOURS_START] ?: 22 * 60 }
    /** Quiet-hours window end as minute-of-day (0..1439). Default 07:00 = 420. */
    val quietHoursEnd: Flow<Int> =
        ctx.dataStore.data.map { it[QUIET_HOURS_END] ?: 7 * 60 }
    /** Active theme mode. Default = SYSTEM. */
    val themeMode: Flow<ThemeMode> = ctx.dataStore.data.map { p ->
        ThemeMode.fromString(p[THEME_MODE])
    }
    /**
     * Last-seen Kuma version for the active server, written by [setActiveServerKumaVersion]
     * after each `info` push. Null until the first push. Read synchronously by
     * KumaSocket via [activeServerKumaVersionOnce] for the heartbeatList default.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeServerKumaVersion: Flow<String?> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else ctx.dataStore.data.map { p -> p[kumaVersionKeyFor(id)] }
    }
    /**
     * True iff the **active server's** token is currently held in plaintext
     * (the keystore was unavailable when we wrote it). Derived fresh from
     * the raw `SERVERS_JSON` + active id, so a server switch automatically
     * re-evaluates without any additional bookkeeping.
     *
     * Drives the `PlaintextTokenBanner` in Settings, whose copy specifically
     * targets the user's *current* session — a global "any saved token is
     * plaintext" view would over-warn (banner up while signed into a
     * properly-encrypted server).
     */
    val tokensStoredPlaintext: Flow<Boolean> = ctx.dataStore.data
        // Pre-filter to the (raw, activeId) pair so unrelated pref edits
        // (notification toggle, theme change, prune cycles) don't re-emit and
        // re-parse the JSON. Only changes to the servers blob or active id
        // can affect this flag's value.
        .map { p -> p[SERVERS_JSON] to resolveActiveId(p) }
        .distinctUntilChanged()
        .map { (raw, activeId) ->
            if (raw == null || activeId == null) return@map false
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("id", -1) != activeId) continue
                    val tok = o.optString("token").takeIf { it.isNotEmpty() && it != "null" }
                        ?: return@map false
                    return@map !TokenCrypto.isEnvelope(tok)
                }
                false
            }.getOrDefault(false)
        }

    suspend fun serverUrlOnce(): String? = serverUrl.first()
    suspend fun tokenOnce(): String? = token.first()
    suspend fun notificationsEnabledOnce(): Boolean = notificationsEnabled.first()
    suspend fun activeServerOnce(): ServerEntry? = activeServer.first()
    suspend fun serversOnce(): List<ServerEntry> = servers.first()
    suspend fun activeServerKumaVersionOnce(): String? = activeServerKumaVersion.first()

    /** All persisted deep-link nonces. Used by Notifications.kt to seed its in-memory store on app start. */
    suspend fun deepLinkNoncesOnce(): Set<String> =
        ctx.dataStore.data.map { it[DEEP_LINK_NONCES] ?: emptySet() }.first()

    /** Persist a single new nonce. */
    suspend fun addDeepLinkNonce(entry: String) = ctx.dataStore.edit { p ->
        val cur = p[DEEP_LINK_NONCES] ?: emptySet()
        p[DEEP_LINK_NONCES] = cur + entry
    }

    /** Remove one or more nonces (consumed or expired). No-op if empty. */
    suspend fun removeDeepLinkNonces(entries: Set<String>) {
        if (entries.isEmpty()) return
        ctx.dataStore.edit { p ->
            val cur = p[DEEP_LINK_NONCES] ?: return@edit
            val next = cur - entries
            if (next.isEmpty()) p.remove(DEEP_LINK_NONCES) else p[DEEP_LINK_NONCES] = next
        }
    }

    /**
     * Persist the Kuma server version reported by the most recent `info` event.
     * Called by KumaCheckApp's serverInfo collector. Cleared by [removeServer].
     */
    suspend fun setActiveServerKumaVersion(version: String?) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        if (version.isNullOrBlank()) p.remove(kumaVersionKeyFor(activeId))
        else p[kumaVersionKeyFor(activeId)] = version
    }

    /**
     * Move any pinned/muted ids stored under the legacy single-server keys
     * onto the per-server key for server id=1 (the implicit "original" server
     * created by the legacy migration in [readServers]). After this runs,
     * each server reads only its own per-server key — adding a second server
     * cannot accidentally erase the first server's pins.
     *
     * Idempotent — a no-op once the legacy keys have been drained, or when
     * no server with id=1 exists (in that case we drop the legacy keys as
     * orphaned, since they cannot be assigned anywhere meaningful).
     */
    suspend fun migrateLegacyPerServerKeysIfNeeded() = ctx.dataStore.edit { p ->
        val legacyPinned = p[LEGACY_PINNED_MONITOR_IDS]
        val legacyMuted = p[LEGACY_MUTED_MONITOR_IDS]
        if (legacyPinned == null && legacyMuted == null) return@edit
        val firstServerExists = readServers(p).any { it.id == 1 }
        if (firstServerExists) {
            if (legacyPinned != null && p[pinnedKeyFor(1)] == null) {
                p[pinnedKeyFor(1)] = legacyPinned
            }
            if (legacyMuted != null && p[mutedKeyFor(1)] == null) {
                p[mutedKeyFor(1)] = legacyMuted
            }
        }
        p.remove(LEGACY_PINNED_MONITOR_IDS)
        p.remove(LEGACY_MUTED_MONITOR_IDS)
    }

    /**
     * Re-encrypt any tokens stored in plaintext (left behind by older builds
     * that pre-dated [TokenCrypto] or by builds running on a device whose
     * keystore was unavailable at write time), and clear any envelopes that
     * no longer decrypt (key rotated, biometric enrollment invalidated the
     * key, app data restored from backup, etc. — the ciphertext is dead
     * weight that would otherwise sit in DataStore forever pretending to be
     * a valid token). Cheap to call repeatedly — a no-op once everything is
     * either a fresh envelope or absent.
     */
    suspend fun migrateTokensIfNeeded() = ctx.dataStore.edit { p ->
        val raw = p[SERVERS_JSON] ?: return@edit
        var hasPlaintext = false
        // Track ids whose envelope is structurally an envelope but doesn't
        // decrypt — those should be cleared (token=null, rawToken=null) so
        // R2-1's writeServers fallback doesn't keep restoring the dead
        // envelope across edits. We handle these *explicitly* here because
        // the fallback path's whole point is to preserve transient-decrypt
        // failures.
        val undecryptableIds = HashSet<Int>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1).takeIf { it >= 0 } ?: continue
                val tok = o.optString("token")
                    .takeIf { it.isNotEmpty() && it != "null" } ?: continue
                if (!TokenCrypto.isEnvelope(tok)) {
                    hasPlaintext = true
                    continue
                }
                if (crypto.decrypt(tok) == null) {
                    undecryptableIds.add(id)
                }
            }
        }
        if (!hasPlaintext && undecryptableIds.isEmpty()) return@edit
        val cleaned = readServers(p).map { e ->
            if (e.id in undecryptableIds) e.copy(token = null, rawToken = null) else e
        }
        writeServers(p, cleaned)
    }

    /**
     * Update the URL of the active server, or — if no server has ever been
     * saved — create one with the next available id. Used by the Login flow's
     * "URL field changed" path.
     *
     * Dedupe: if another saved entry already points at this URL, switch
     * active to it and drop the current entry if it was an empty placeholder
     * (created by `beginAddServer`). This stops "Add another server" → log
     * back into the same URL from leaving a phantom duplicate behind.
     */
    suspend fun setServer(url: String) = ctx.dataStore.edit { p ->
        val list = readServers(p).toMutableList()
        val activeId = resolveActiveId(p)
        val idx = list.indexOfFirst { it.id == activeId }

        val normalized = url.trimEnd('/')
        val existingIdx = list.indexOfFirst {
            it.id != activeId && it.url.trimEnd('/').equals(normalized, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val existingId = list[existingIdx].id
            // Drop a current placeholder entry (no URL or no creds) so we don't
            // accumulate empty rows in the picker. Real entries stay in case
            // the user wanted to keep them.
            if (idx >= 0) {
                val cur = list[idx]
                if (cur.url.isBlank() || (cur.username == null && cur.token == null)) {
                    list.removeAt(idx)
                }
            }
            p[ACTIVE_SERVER_ID] = existingId
            writeServers(p, list)
            return@edit
        }

        if (idx >= 0) {
            list[idx] = list[idx].copy(url = url)
        } else {
            val newId = (list.maxOfOrNull { it.id } ?: 0) + 1
            list.add(ServerEntry(newId, url, null, null))
            p[ACTIVE_SERVER_ID] = newId
        }
        writeServers(p, list)
    }

    /**
     * Persist a successful login. Updates the active server entry's
     * username + token. If no server is active yet (first ever login),
     * promotes the most recent URL into a fresh entry.
     */
    suspend fun setSession(username: String, token: String) = ctx.dataStore.edit { p ->
        val list = readServers(p).toMutableList()
        val activeId = resolveActiveId(p) ?: list.firstOrNull()?.id
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(username = username, token = token)
        } else {
            // Defensive fallback: setServer() should have run first in the
            // login flow, but a racing cancelAddServer() (e.g. user back-tap
            // mid-flight) can drop the placeholder before we get here. We
            // also hit this branch on first-ever login if neither setServer
            // nor a legacy migration produced a list entry. Either way,
            // create a fresh entry so the successful login isn't silently
            // dropped. URL preference order: existing active id's URL (if
            // any), then legacy URL, then empty (the user can fix it later
            // in Settings — better than discarding their credentials).
            val url = activeId?.let { id -> list.firstOrNull { it.id == id }?.url }
                ?: p[LEGACY_SERVER_URL]
                ?: ""
            val newId = activeId
                ?: ((list.maxOfOrNull { it.id } ?: 0) + 1)
            list.add(ServerEntry(newId, url, username, token))
            p[ACTIVE_SERVER_ID] = newId
        }
        writeServers(p, list)
    }

    /** Switch the active server. Caller is responsible for triggering reconnect. */
    suspend fun setActiveServerId(id: Int) = ctx.dataStore.edit { it[ACTIVE_SERVER_ID] = id }

    /** Remove a saved server entry. If it was the active one, falls back to first remaining. */
    suspend fun removeServer(id: Int) = ctx.dataStore.edit { p ->
        val list = readServers(p).filter { it.id != id }
        writeServers(p, list)
        if (resolveActiveId(p) == id) {
            val next = list.firstOrNull()
            if (next != null) p[ACTIVE_SERVER_ID] = next.id
            else p.remove(ACTIVE_SERVER_ID)
        }
        purgePerServerKeys(p, id)
    }

    /**
     * Allocate a new server entry without an active session yet (for the
     * "+ add server" flow). Defensively purges any orphaned per-server keys
     * for the same id first — id allocation is `(maxId ?: 0) + 1`, so
     * removing the highest-id server then immediately adding a new one
     * reuses the id; without this pre-purge the new entry would inherit the
     * removed server's muted/notified/ack state.
     *
     * Idempotent (PD3): if the active server is already a blank-URL placeholder
     * created by a prior call (e.g. the LOGIN_ADD composable's
     * `LaunchedEffect(Unit)` re-firing after a process-death restore), reuse
     * its id instead of allocating a second placeholder. The `url` arg is only
     * used when allocating a fresh entry — passing a non-empty url here while
     * a placeholder exists doesn't mutate the placeholder's url.
     */
    suspend fun beginAddServer(url: String): Int {
        var newId = -1
        ctx.dataStore.edit { p ->
            val list = readServers(p).toMutableList()
            val activeId = resolveActiveId(p)
            val existingPlaceholder = activeId?.let { id ->
                list.firstOrNull { it.id == id && it.url.isBlank() && it.username == null && it.token == null }
            }
            if (existingPlaceholder != null) {
                newId = existingPlaceholder.id
                return@edit
            }
            newId = (list.maxOfOrNull { it.id } ?: 0) + 1
            purgePerServerKeys(p, newId)
            list.add(ServerEntry(newId, url, null, null))
            writeServers(p, list)
            p[ACTIVE_SERVER_ID] = newId
        }
        return newId
    }

    /**
     * Drop the active server entry if it's an empty placeholder (no URL —
     * left behind by `beginAddServer` when the user backs out of LOGIN_ADD
     * before completing). Active id is pivoted to the most recent remaining
     * server, or cleared if none exist.
     */
    suspend fun cancelAddServer() = ctx.dataStore.edit { p ->
        val list = readServers(p).toMutableList()
        val activeId = resolveActiveId(p) ?: return@edit
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx < 0) return@edit
        if (list[idx].url.isBlank()) {
            list.removeAt(idx)
            writeServers(p, list)
            val next = list.lastOrNull()
            if (next != null) p[ACTIVE_SERVER_ID] = next.id
            else p.remove(ACTIVE_SERVER_ID)
            purgePerServerKeys(p, activeId)
        }
    }

    /**
     * Centralized purge of every per-server key for [id]. Keep this in sync
     * whenever a new per-server pref class is added — every removeServer /
     * beginAddServer / cancelAddServer call routes through here so a future
     * pref can't quietly start leaking across server reuse.
     */
    private fun purgePerServerKeys(p: androidx.datastore.preferences.core.MutablePreferences, id: Int) {
        p.remove(pinnedKeyFor(id))
        p.remove(mutedKeyFor(id))
        p.remove(ackIncidentKeyFor(id))
        p.remove(notifiedStatusKeyFor(id))
        p.remove(incidentLogKeyFor(id))
        p.remove(kumaVersionKeyFor(id))
    }
    suspend fun setNotificationsEnabled(enabled: Boolean) = ctx.dataStore.edit {
        it[NOTIFICATIONS_ENABLED] = enabled
    }
    suspend fun setPinnedMonitorIds(ids: Set<Int>) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        p[pinnedKeyFor(activeId)] = ids.mapTo(HashSet()) { it.toString() }
        // Note: do NOT drop LEGACY_PINNED_MONITOR_IDS here — other servers may
        // still be reading it as a fallback until [migrateLegacyPerServerKeysIfNeeded]
        // copies it onto the original (id=1) server's per-server key.
    }
    suspend fun setMutedMonitorIds(ids: Set<Int>) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        p[mutedKeyFor(activeId)] = ids.mapTo(HashSet()) { it.toString() }
    }
    /**
     * Atomic add/remove of a single id on the active server's pinned set.
     * Use instead of `set...MonitorIds(set.first().toMutableSet().also { … })`
     * — the read-then-write pattern races: two rapid toggles can drop a
     * mutation when the second one writes a stale snapshot. This helper
     * does the read-modify-write inside one `dataStore.edit` so the latest
     * persisted value is always the basis for the update.
     */
    suspend fun togglePinnedMonitor(id: Int, pinned: Boolean) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val key = pinnedKeyFor(activeId)
        val cur = p[key] ?: emptySet()
        val s = id.toString()
        p[key] = if (pinned) cur + s else cur - s
    }
    /** See [togglePinnedMonitor]. Same atomicity reasoning. */
    suspend fun toggleMutedMonitor(id: Int, muted: Boolean) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val key = mutedKeyFor(activeId)
        val cur = p[key] ?: emptySet()
        val s = id.toString()
        p[key] = if (muted) cur + s else cur - s
    }
    /** Mark an incident (per-monitor + per-start-time) as acknowledged. */
    suspend fun acknowledgeIncident(monitorId: Int, startTimestampMs: Long) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val key = ackIncidentKeyFor(activeId)
        val cur = p[key] ?: emptySet()
        p[key] = cur + "${monitorId}_$startTimestampMs"
    }
    /**
     * Append [entry] to the active server's incident log, deduped by
     * (monitorId, timestampMs) so re-imports of the heartbeatList seed are
     * idempotent. The log is kept newest-first and capped at
     * [INCIDENT_LOG_MAX] — older entries roll off automatically.
     *
     * Returns silently with no log if no server is active.
     */
    suspend fun appendIncident(entry: IncidentLogEntry) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val key = incidentLogKeyFor(activeId)
        val existing = decodeIncidentLog(p[key])
        if (existing.any { it.monitorId == entry.monitorId && it.timestampMs == entry.timestampMs }) {
            return@edit
        }
        val updated = (listOf(entry) + existing).take(INCIDENT_LOG_MAX)
        p[key] = encodeIncidentLog(updated)
    }

    /** Clear the active server's incident log. */
    suspend fun clearIncidentLog() = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        p.remove(incidentLogKeyFor(activeId))
    }

    /**
     * Delete one or more incident log entries that exactly match the
     * given (monitorId, timestampMs) tuples. No-op if none match. Used by
     * the per-incident detail screen's delete action — passes both the
     * DOWN start and (when present) the UP recovery timestamps so a
     * single tap removes the whole incident pair.
     */
    suspend fun deleteIncidents(
        monitorId: Int,
        timestampsMs: Set<Long>,
    ) = ctx.dataStore.edit { p ->
        if (timestampsMs.isEmpty()) return@edit
        val activeId = resolveActiveId(p) ?: return@edit
        val key = incidentLogKeyFor(activeId)
        val existing = decodeIncidentLog(p[key])
        val pruned = existing.filterNot {
            it.monitorId == monitorId && it.timestampMs in timestampsMs
        }
        if (pruned.size != existing.size) {
            if (pruned.isEmpty()) p.remove(key)
            else p[key] = encodeIncidentLog(pruned)
        }
    }

    /**
     * Drop muted/notified/ack-incident entries for monitor ids that no longer
     * exist on the active server. The dedupe / ack sets would otherwise grow
     * forever as the user creates and deletes monitors over time.
     *
     * Caller passes both the *current* live id set AND the server id that set
     * came from. The prune only runs if [forServerId] still matches the
     * resolved-active server inside the edit — otherwise we'd prune the new
     * server's per-server keys based on a stale snapshot from the previous
     * server (race window between active-server switch and the new socket
     * pushing its first monitorList).
     *
     * No-ops on `forServerId == null` so the caller can pass the snapshot's
     * source id without an extra null-check.
     */
    suspend fun pruneStaleMonitorIds(liveIds: Set<Int>, forServerId: Int?) = ctx.dataStore.edit { p ->
        if (forServerId == null) return@edit
        val activeId = resolveActiveId(p) ?: return@edit
        if (activeId != forServerId) return@edit
        val mutedKey = mutedKeyFor(activeId)
        p[mutedKey]?.let { cur ->
            val pruned = cur.filterTo(HashSet()) { (it.toIntOrNull() ?: -1) in liveIds }
            if (pruned.size != cur.size) p[mutedKey] = pruned
        }
        val notifiedKey = notifiedStatusKeyFor(activeId)
        p[notifiedKey]?.let { cur ->
            val pruned = cur.filterTo(HashSet()) { entry ->
                val mid = entry.substringBefore(':').toIntOrNull() ?: return@filterTo false
                mid in liveIds
            }
            if (pruned.size != cur.size) p[notifiedKey] = pruned
        }
        val ackKey = ackIncidentKeyFor(activeId)
        p[ackKey]?.let { cur ->
            val pruned = cur.filterTo(HashSet()) { entry ->
                val mid = entry.substringBefore('_').toIntOrNull() ?: return@filterTo false
                mid in liveIds
            }
            if (pruned.size != cur.size) p[ackKey] = pruned
        }
        val pinnedKey = pinnedKeyFor(activeId)
        p[pinnedKey]?.let { cur ->
            val pruned = cur.filterTo(HashSet()) { (it.toIntOrNull() ?: -1) in liveIds }
            if (pruned.size != cur.size) p[pinnedKey] = pruned
        }
        val incidentKey = incidentLogKeyFor(activeId)
        p[incidentKey]?.let { cur ->
            val existing = decodeIncidentLog(cur)
            val pruned = existing.filter { it.monitorId in liveIds }
            if (pruned.size != existing.size) {
                if (pruned.isEmpty()) p.remove(incidentKey)
                else p[incidentKey] = encodeIncidentLog(pruned)
            }
        }
    }

    /**
     * Targeted single-monitor cleanup. Called from the
     * `deleteMonitorFromList` push (and the explicit delete RPC's success
     * path) so per-server state for the deleted monitor doesn't survive.
     * Mirrors [pruneStaleMonitorIds] but operates on one id without
     * needing a snapshot of the whole live set — the server-pushed
     * delete event is authoritative.
     */
    suspend fun forgetMonitor(monitorId: Int) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val idStr = monitorId.toString()
        p[mutedKeyFor(activeId)]?.let { cur ->
            if (idStr in cur) p[mutedKeyFor(activeId)] = cur - idStr
        }
        p[pinnedKeyFor(activeId)]?.let { cur ->
            if (idStr in cur) p[pinnedKeyFor(activeId)] = cur - idStr
        }
        p[notifiedStatusKeyFor(activeId)]?.let { cur ->
            val prefix = "$monitorId:"
            val pruned = cur.filterNotTo(HashSet()) { it.startsWith(prefix) }
            if (pruned.size != cur.size) p[notifiedStatusKeyFor(activeId)] = pruned
        }
        p[ackIncidentKeyFor(activeId)]?.let { cur ->
            val prefix = "${monitorId}_"
            val pruned = cur.filterNotTo(HashSet()) { it.startsWith(prefix) }
            if (pruned.size != cur.size) p[ackIncidentKeyFor(activeId)] = pruned
        }
        p[incidentLogKeyFor(activeId)]?.let { cur ->
            val existing = decodeIncidentLog(cur)
            val pruned = existing.filter { it.monitorId != monitorId }
            if (pruned.size != existing.size) {
                if (pruned.isEmpty()) p.remove(incidentLogKeyFor(activeId))
                else p[incidentLogKeyFor(activeId)] = encodeIncidentLog(pruned)
            }
        }
    }

    /**
     * Record the last status we posted a notification for on [monitorId].
     * Replaces any prior entry for that monitor; scoped to the active server.
     */
    suspend fun setNotifiedStatus(monitorId: Int, statusName: String) = ctx.dataStore.edit { p ->
        val activeId = resolveActiveId(p) ?: return@edit
        val key = notifiedStatusKeyFor(activeId)
        val prefix = "$monitorId:"
        val cur = p[key] ?: emptySet()
        val next = cur.filterNotTo(HashSet()) { it.startsWith(prefix) }
        next.add("$prefix$statusName")
        p[key] = next
    }
    suspend fun setQuietHoursEnabled(enabled: Boolean) = ctx.dataStore.edit {
        it[QUIET_HOURS_ENABLED] = enabled
    }
    suspend fun setQuietHoursStart(minuteOfDay: Int) = ctx.dataStore.edit {
        it[QUIET_HOURS_START] = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    }
    suspend fun setQuietHoursEnd(minuteOfDay: Int) = ctx.dataStore.edit {
        it[QUIET_HOURS_END] = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    }
    suspend fun setThemeMode(mode: ThemeMode) = ctx.dataStore.edit {
        it[THEME_MODE] = mode.name
    }
    /**
     * Clear only the JWT for the active server — keep username so the user
     * only re-enters password on next sign-in.
     */
    suspend fun clearToken() = ctx.dataStore.edit { p ->
        val list = readServers(p).toMutableList()
        val activeId = resolveActiveId(p) ?: return@edit
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            // Clear both the decoded plaintext AND the stored envelope so
            // writeServers' R2-1 fallback doesn't restore the just-cleared
            // envelope from rawToken.
            list[idx] = list[idx].copy(token = null, rawToken = null)
            writeServers(p, list)
        }
        // Also clean legacy key in case migration hasn't run yet.
        p.remove(LEGACY_TOKEN)
    }

    /** Clear username + token for the active server. */
    suspend fun clearSession() = ctx.dataStore.edit { p ->
        val list = readServers(p).toMutableList()
        val activeId = resolveActiveId(p) ?: return@edit
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            // Clear rawToken too so writeServers doesn't restore the envelope
            // from the R2-1 fallback path. See clearToken for the same reason.
            list[idx] = list[idx].copy(username = null, token = null, rawToken = null)
            writeServers(p, list)
        }
        // A different user may sign in next; drop per-server prefs so their
        // pins / mutes / ack-incidents / notified-status from the prior
        // session don't silently apply to the new account on the same URL.
        purgePerServerKeys(p, activeId)
        p.remove(LEGACY_TOKEN)
        p.remove(LEGACY_USERNAME)
    }

    // --- Internals ---

    private fun readServers(p: Preferences): List<ServerEntry> {
        val raw = p[SERVERS_JSON]
        if (raw.isNullOrBlank()) {
            // Migrate legacy single-server config the first time we read.
            val url = p[LEGACY_SERVER_URL]?.takeIf { it.isNotEmpty() }
            if (url != null) {
                return listOf(
                    ServerEntry(
                        id = 1,
                        url = url,
                        username = p[LEGACY_USERNAME],
                        token = p[LEGACY_TOKEN],
                    )
                )
            }
            return emptyList()
        }
        // Top-level parse: if this throws, the entire blob is unparseable and
        // we must NOT silently return empty — that would let the next
        // writeServers clobber it with `[]`. Caller (writeServers /
        // backupCorruptServersIfNeeded) is responsible for stashing the raw
        // value to SERVERS_JSON_BACKUP before any subsequent write.
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        // Per-entry parse: a single bad row no longer poisons the whole list.
        // Each entry runs its own runCatching so a deserialization failure on
        // one server (e.g. corrupted token bytes) doesn't drop unrelated
        // valid servers.
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                arr.optJSONObject(i)
                    ?.let { ServerEntry.fromJson(it) }
                    ?.let { entry ->
                        // Keep the raw stored token alongside the decoded plaintext
                        // so writeServers can preserve an undecryptable envelope
                        // across unrelated pref edits (R2-1).
                        entry.copy(
                            token = decodeStoredToken(entry.token),
                            rawToken = entry.token,
                        )
                    }
            }.getOrNull()
        }
    }

    /**
     * True iff [SERVERS_JSON] holds a non-blank value that fails to parse as
     * a JSON array. Tells [writeServers] (and external recovery hooks) that
     * we must back up the raw blob before overwriting it.
     */
    private fun serversBlobIsCorrupt(p: Preferences): Boolean {
        val raw = p[SERVERS_JSON] ?: return false
        if (raw.isBlank()) return false
        return runCatching { JSONArray(raw) }.isFailure
    }

    /**
     * Convert a stored token back to plaintext for in-memory use. Envelopes
     * (`enc1:...`) are decrypted; legacy plaintext is passed through. A failed
     * decrypt returns null so the user is forced to re-login rather than
     * acting on a corrupt token.
     */
    private fun decodeStoredToken(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return if (TokenCrypto.isEnvelope(stored)) crypto.decrypt(stored) else stored
    }

    /**
     * Encrypts a plaintext token for storage. Returns null when the keystore
     * is unavailable rather than persisting plaintext (S1) — the in-memory
     * `ServerEntry.token` still works for the current session; on app
     * restart the user simply has to sign in again. Persisting plaintext
     * would leave the JWT readable to root / backup-extractor / any process
     * with file access, which is exactly the threat the keystore wraps
     * against.
     */
    private fun encodeTokenForStorage(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        if (TokenCrypto.isEnvelope(plain)) return plain
        val encrypted = crypto.encrypt(plain)
        if (encrypted == null) keystoreUnavailableForWrite.value = true
        else keystoreUnavailableForWrite.value = false
        return encrypted
    }

    /**
     * True iff the most recent attempt to encrypt a token for storage
     * failed (Keystore transient unavailability). Surfaces a Settings
     * banner so the user knows the next sign-in won't survive an app
     * restart. Resets to false on the next successful encrypt.
     */
    val keystoreUnavailableForWrite = MutableStateFlow(false)

    private fun resolveActiveId(p: Preferences): Int? {
        val explicit = p[ACTIVE_SERVER_ID]
        if (explicit != null) return explicit
        // Pre-migration default: legacy single-server is implicit id=1.
        val hasLegacy = !p[LEGACY_SERVER_URL].isNullOrEmpty()
        return if (hasLegacy) 1 else null
    }

    private fun writeServers(p: androidx.datastore.preferences.core.MutablePreferences, list: List<ServerEntry>) {
        // Before clobbering the existing blob, snapshot it to a backup key if
        // the current value can't be parsed — otherwise we'd silently destroy
        // every saved server when the next pref write fires (e.g. notification
        // toggle). The backup persists across writes; the user (or a future
        // recovery flow) can copy it back manually.
        if (serversBlobIsCorrupt(p)) {
            val raw = p[SERVERS_JSON]
            // P1: refresh the backup whenever the live blob is corrupt and
            // differs from the existing backup. The original "only write if
            // backup is null" guard meant a second corruption (after a
            // recovery cycle) would silently keep the stale first-corruption
            // backup, leaving recovery tools with old data.
            if (raw != null && p[SERVERS_JSON_BACKUP] != raw) {
                p[SERVERS_JSON_BACKUP] = raw
            }
        }
        val arr = JSONArray()
        list.forEach { e ->
            val toPersist = if (e.token != null) {
                // Caller has a new plaintext token to store. Try to encrypt;
                // if Keystore is unavailable (S1), persist null rather than
                // falling back to the stale `rawToken` envelope on disk —
                // that would silently keep an old JWT around when the user
                // believes they just stored a new one. The in-memory token
                // remains usable for this session.
                encodeTokenForStorage(e.token)
            } else {
                // No new token. If we previously read a valid envelope from
                // disk for this entry (decrypt was momentarily unavailable),
                // persist the original envelope rather than blanking it.
                // The explicit migration / clearToken / clearSession paths
                // still work because they pass an entry whose `rawToken` is
                // also null.
                e.rawToken?.takeIf { TokenCrypto.isEnvelope(it) }
            }
            arr.put(e.copy(token = toPersist).toJson())
        }
        p[SERVERS_JSON] = arr.toString()
        // Once we've written the modern key once, drop legacy fallbacks so we
        // never accidentally re-read them.
        p.remove(LEGACY_SERVER_URL)
        p.remove(LEGACY_USERNAME)
        p.remove(LEGACY_TOKEN)
        p.remove(TOKENS_STORED_PLAINTEXT_LEGACY)
    }

    private fun decodeIncidentLog(raw: String?): List<IncidentLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mid = o.optInt("monitorId", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val ts = o.optLong("timestampMs", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
                val statusName = o.optString("status").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val status = runCatching { MonitorStatus.valueOf(statusName) }.getOrNull() ?: return@mapNotNull null
                IncidentLogEntry(
                    monitorId = mid,
                    monitorName = o.optString("monitorName", "Monitor $mid"),
                    status = status,
                    timestampMs = ts,
                    msg = o.optString("msg", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeIncidentLog(entries: List<IncidentLogEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("monitorId", e.monitorId)
                    .put("monitorName", e.monitorName)
                    .put("status", e.status.name)
                    .put("timestampMs", e.timestampMs)
                    .put("msg", e.msg)
            )
        }
        return arr.toString()
    }

    private companion object {
        const val INCIDENT_LOG_MAX = 500
    }
}
