package app.kumacheck.data.socket

import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.model.MonitorStatus
import org.json.JSONArray
import org.json.JSONObject

/** Tiny tolerant JSON helpers — Kuma sometimes returns nulls as the string "null", */
/** uses both casings, and mixes types (int vs. string) across versions. */
internal object KumaJson {

    fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() && it != "null" } else null

    fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) {
            when (val v = get(key)) {
                is Int -> v
                is Long -> v.toInt()
                is String -> v.toIntOrNull()
                is Number -> v.toInt()
                else -> null
            }
        } else null

    fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) {
            // P4: drop NaN / Infinity. The raw `Number.toDouble()` and
            // `String.toDoubleOrNull()` happily round-trip these values, and
            // `Heartbeat.ping`, uptime, and avgPing all flow into Compose
            // chart math without re-validating — one bogus payload can poison
            // every chart that references it.
            when (val v = get(key)) {
                is Number -> v.toDouble().takeIf { it.isFinite() }
                is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> null
            }
        } else null

    fun JSONObject.optBoolOrFalse(key: String): Boolean = optBoolean(key, false)

    fun parseMonitor(o: JSONObject): Monitor {
        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tag = arr.optJSONObject(i) ?: continue
                tag.optStringOrNull("name")?.let(tags::add)
            }
        }
        // P6: pull previously round-tripped fields into the typed model
        // so UI features can filter on them.
        val notificationIds = o.optJSONArray("notificationIDList")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.opt(i)) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
            }
        }
        val acceptedCodes = o.optJSONArray("accepted_statuscodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.opt(i)?.toString() }
        }
        return Monitor(
            id = o.getInt("id"),
            // P3: parser previously accepted `""` (the JSON `optString` default
            // when the key is missing or empty), which surfaces as a blank row
            // in every list. Surface a deterministic fallback so we never
            // render a "ghost" monitor without a name.
            name = o.optStringOrNull("name") ?: "(unnamed)",
            type = o.optString("type", "unknown"),
            active = o.optBoolOrFalse("active"),
            forceInactive = o.optBoolOrFalse("forceInactive"),
            parent = o.optIntOrNull("parent"),
            tags = tags,
            hostname = o.optStringOrNull("hostname"),
            url = o.optStringOrNull("url"),
            port = o.optIntOrNull("port"),
            notificationIDList = notificationIds,
            acceptedStatusCodes = acceptedCodes,
            keyword = o.optStringOrNull("keyword"),
            expiryNotification = if (o.has("expiryNotification") && !o.isNull("expiryNotification"))
                o.optBoolean("expiryNotification") else null,
            maxredirects = o.optIntOrNull("maxredirects"),
            ignoreTls = if (o.has("ignoreTls") && !o.isNull("ignoreTls"))
                o.optBoolean("ignoreTls") else null,
            httpBodyEncoding = o.optStringOrNull("httpBodyEncoding"),
            authMethod = o.optStringOrNull("authMethod"),
            packetSize = o.optIntOrNull("packetSize"),
            gameDig = o.optStringOrNull("game"),
            dockerHost = o.optStringOrNull("docker_host"),
            dockerContainer = o.optStringOrNull("docker_container"),
        )
    }

    /** Parse server `monitorList` event payload (dict keyed by monitor id). */
    fun parseMonitorList(o: JSONObject): Map<Int, Monitor> {
        val out = HashMap<Int, Monitor>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next() as String
            val m = o.optJSONObject(k) ?: continue
            val parsed = runCatching { parseMonitor(m) }.getOrNull() ?: continue
            out[parsed.id] = parsed
        }
        return out
    }

    /**
     * Parse a heartbeat from any of:
     *   - `heartbeat` push event payload (uses monitorID camelCase)
     *   - `getMonitorBeats` data array entry (uses monitor_id snake_case)
     */
    fun parseHeartbeat(o: JSONObject): Heartbeat {
        val mid = o.optIntOrNull("monitorID")
            ?: o.optIntOrNull("monitor_id")
            ?: error("heartbeat missing monitor id: $o")
        val timeStr = o.optString("time")
        return Heartbeat(
            monitorId = mid,
            status = MonitorStatus.from(o.optInt("status", -1)),
            time = timeStr,
            msg = o.optString("msg", ""),
            ping = o.optDoubleOrNull("ping"),
            important = o.optBoolOrFalse("important"),
            // P2: parse once at ingest. Subsequent sort/compare use timeMs
            // instead of lexicographic compare on the raw string, which
            // breaks the moment Kuma mixes formats (Z-suffix vs naïve
            // local, ISO vs the legacy "yyyy-MM-dd HH:mm:ss" form).
            timeMs = app.kumacheck.util.parseBeatTime(timeStr),
        )
    }

    /**
     * `heartbeatList` push: positional args (monitorId:Int, beats:JSONArray, overwrite:Boolean).
     *
     * Parse failures are returned via [onParseFailure] (default: ignore) so the
     * caller in `KumaSocket` can route them to logcat without forcing
     * [android.util.Log] into this JVM-pure helper (which is exercised by unit
     * tests where Log is unmocked).
     */
    fun parseHeartbeatList(
        args: Array<Any?>,
        onParseFailure: (Throwable) -> Unit = {},
    ): Pair<Int, List<Heartbeat>>? {
        val monitorId = (args.getOrNull(0) as? Number)?.toInt()
            ?: (args.getOrNull(0) as? String)?.toIntOrNull()
            ?: return null
        val arr = args.getOrNull(1) as? JSONArray ?: return null
        val beats = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                runCatching { parseHeartbeat(it) }.onFailure(onParseFailure).getOrNull()
            }
        }
        return monitorId to beats
    }
}
