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
            when (val v = get(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
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
        return Monitor(
            id = o.getInt("id"),
            name = o.optString("name"),
            type = o.optString("type", "unknown"),
            active = o.optBoolOrFalse("active"),
            forceInactive = o.optBoolOrFalse("forceInactive"),
            parent = o.optIntOrNull("parent"),
            tags = tags,
            hostname = o.optStringOrNull("hostname"),
            url = o.optStringOrNull("url"),
            port = o.optIntOrNull("port"),
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
        return Heartbeat(
            monitorId = mid,
            status = MonitorStatus.from(o.optInt("status", -1)),
            time = o.optString("time"),
            msg = o.optString("msg", ""),
            ping = o.optDoubleOrNull("ping"),
            important = o.optBoolOrFalse("important"),
        )
    }

    /** `heartbeatList` push: positional args (monitorId:Int, beats:JSONArray, overwrite:Boolean). */
    fun parseHeartbeatList(args: Array<Any?>): Pair<Int, List<Heartbeat>>? {
        val monitorId = (args.getOrNull(0) as? Number)?.toInt()
            ?: (args.getOrNull(0) as? String)?.toIntOrNull()
            ?: return null
        val arr = args.getOrNull(1) as? JSONArray ?: return null
        val beats = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { runCatching { parseHeartbeat(it) }.getOrNull() }
        }
        return monitorId to beats
    }
}
