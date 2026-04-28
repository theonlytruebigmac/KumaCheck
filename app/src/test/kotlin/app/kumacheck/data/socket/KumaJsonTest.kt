package app.kumacheck.data.socket

import app.kumacheck.data.model.MonitorStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KumaJsonTest {

    @Test fun `parseMonitor handles minimal payload`() {
        val o = JSONObject(
            """{"id":42, "name":"Example", "type":"http", "active":true,
                "url":"https://example.com"}""",
        )
        val m = KumaJson.parseMonitor(o)
        assertEquals(42, m.id)
        assertEquals("Example", m.name)
        assertEquals("http", m.type)
        assertEquals(true, m.active)
        assertEquals("https://example.com", m.url)
        assertNull(m.hostname)
        assertNull(m.port)
        assertEquals(emptyList<String>(), m.tags)
    }

    @Test fun `parseMonitor extracts tag names`() {
        val o = JSONObject(
            """{"id":1, "name":"x", "type":"http", "active":true,
                "tags":[{"name":"prod"},{"name":"public"}]}""",
        )
        val m = KumaJson.parseMonitor(o)
        assertEquals(listOf("prod", "public"), m.tags)
    }

    @Test fun `parseMonitor handles port as integer string`() {
        // Some Kuma versions send port as a string. Tolerant parsing required.
        val asString = JSONObject("""{"id":1, "name":"x", "type":"port", "active":true,
            "hostname":"db.local", "port":"5432"}""")
        val asInt = JSONObject("""{"id":1, "name":"x", "type":"port", "active":true,
            "hostname":"db.local", "port":5432}""")
        assertEquals(5432, KumaJson.parseMonitor(asString).port)
        assertEquals(5432, KumaJson.parseMonitor(asInt).port)
    }

    @Test fun `parseMonitor treats string null as null`() {
        // Kuma occasionally serializes nulls as the string "null".
        val o = JSONObject("""{"id":1, "name":"x", "type":"http", "active":true,
            "hostname":"null"}""")
        assertNull(KumaJson.parseMonitor(o).hostname)
    }

    @Test fun `parseMonitorList builds id-keyed map`() {
        val o = JSONObject("""{
            "1":{"id":1, "name":"a", "type":"http", "active":true},
            "2":{"id":2, "name":"b", "type":"port", "active":false}
        }""")
        val map = KumaJson.parseMonitorList(o)
        assertEquals(2, map.size)
        assertEquals("a", map[1]?.name)
        assertEquals(false, map[2]?.active)
    }

    @Test fun `parseHeartbeat handles camelCase monitorID`() {
        // Push event format.
        val o = JSONObject("""{"monitorID":42, "status":1, "time":"2024-01-01 10:00:00",
            "msg":"ok", "ping":37.4, "important":false}""")
        val hb = KumaJson.parseHeartbeat(o)
        assertEquals(42, hb.monitorId)
        assertEquals(MonitorStatus.UP, hb.status)
        assertEquals(37.4, hb.ping ?: -1.0, 0.001)
        assertEquals(false, hb.important)
    }

    @Test fun `parseHeartbeat handles snake_case monitor_id`() {
        // getMonitorBeats format.
        val o = JSONObject("""{"monitor_id":7, "status":0, "time":"2024-01-01 10:00:00",
            "msg":"timeout", "important":true}""")
        val hb = KumaJson.parseHeartbeat(o)
        assertEquals(7, hb.monitorId)
        assertEquals(MonitorStatus.DOWN, hb.status)
        assertEquals(true, hb.important)
        assertNull(hb.ping)
    }

    @Test fun `parseHeartbeat throws on missing monitor id`() {
        val o = JSONObject("""{"status":1, "time":"2024-01-01 10:00:00", "msg":""}""")
        var threw = false
        try { KumaJson.parseHeartbeat(o) } catch (_: Throwable) { threw = true }
        assertTrue("expected to throw on missing monitor id", threw)
    }

    @Test fun `MonitorStatus from int handles known and unknown codes`() {
        assertEquals(MonitorStatus.DOWN, MonitorStatus.from(0))
        assertEquals(MonitorStatus.UP, MonitorStatus.from(1))
        assertEquals(MonitorStatus.PENDING, MonitorStatus.from(2))
        assertEquals(MonitorStatus.MAINTENANCE, MonitorStatus.from(3))
        assertEquals(MonitorStatus.UNKNOWN, MonitorStatus.from(99))
        assertEquals(MonitorStatus.UNKNOWN, MonitorStatus.from(-1))
    }

    @Test fun `parseMonitorList skips malformed entries without throwing`() {
        val o = JSONObject("""{
            "1":{"id":1, "name":"a", "type":"http", "active":true},
            "bad":"not an object",
            "2":{"id":2, "name":"b", "type":"port", "active":true}
        }""")
        val map = KumaJson.parseMonitorList(o)
        assertEquals(2, map.size)
        assertNotNull(map[1])
        assertNotNull(map[2])
    }

    @Test fun `parseHeartbeat tolerates missing optional fields`() {
        val o = JSONObject("""{"monitorID":5, "status":1, "time":"2024-01-01"}""")
        val hb = KumaJson.parseHeartbeat(o)
        assertEquals(5, hb.monitorId)
        assertEquals("", hb.msg)
        assertNull(hb.ping)
        assertEquals(false, hb.important)
    }

    @Test fun `parseHeartbeat treats null status as UNKNOWN`() {
        val o = JSONObject("""{"monitorID":5, "time":"x"}""")
        assertEquals(MonitorStatus.UNKNOWN, KumaJson.parseHeartbeat(o).status)
    }

    @Test fun `parseHeartbeatList ignores the overwrite arg (consumed by KumaSocket)`() {
        // The third positional arg (`overwrite`) is consumed by
        // KumaSocket.heartbeatList handler via resolveOverwrite — the parser
        // intentionally returns only (monitorId, beats). Test documents that
        // contract; the prior version of this test asserted on `parseList`'s
        // overwrite handling but parseList doesn't return it.
        val arr = JSONArray("""[
            {"monitorID":3, "status":1, "time":"2024-01-01 09:00:00", "important":true},
            {"monitorID":3, "status":0, "time":"2024-01-01 10:00:00", "important":true}
        ]""")
        val withFlag = KumaJson.parseHeartbeatList(arrayOf(3, arr, true))
        val withoutFlag = KumaJson.parseHeartbeatList(arrayOf(3, arr))
        assertNotNull(withFlag)
        assertNotNull(withoutFlag)
        assertEquals(withFlag!!.second.size, withoutFlag!!.second.size)
        assertEquals(3, withFlag.first)
    }

    @Test fun `parseHeartbeatList accepts string monitor id`() {
        val arr = JSONArray("""[{"monitorID":1, "status":1, "time":"x"}]""")
        val pair = KumaJson.parseHeartbeatList(arrayOf("7", arr))
        assertNotNull(pair)
        assertEquals(7, pair!!.first)
    }

    @Test fun `parseHeartbeatList rejects bad shapes`() {
        // Missing monitor id
        assertNull(KumaJson.parseHeartbeatList(emptyArray()))
        // Non-numeric monitor id
        assertNull(KumaJson.parseHeartbeatList(arrayOf("not-a-number")))
        // Missing array
        assertNull(KumaJson.parseHeartbeatList(arrayOf(1, "not-an-array")))
    }

    @Test fun `parseHeartbeatList drops malformed beats but keeps good ones`() {
        val arr = JSONArray("""[
            {"monitorID":1, "status":1, "time":"x"},
            {"status":0, "time":"y"},
            {"monitorID":1, "status":0, "time":"z"}
        ]""")
        val pair = KumaJson.parseHeartbeatList(arrayOf(1, arr))
        assertNotNull(pair)
        assertEquals(2, pair!!.second.size)
    }

    @Test fun `parseMonitorList tolerates an empty payload`() {
        val map = KumaJson.parseMonitorList(JSONObject("{}"))
        assertEquals(0, map.size)
    }

    // ---- resolveOverwrite (R2-2 / T1) ----

    @Test fun `resolveOverwrite honors explicit true regardless of version`() {
        assertTrue(resolveOverwrite(explicit = true, liveVersion = "1.5.0", cachedVersion = null))
        assertTrue(resolveOverwrite(explicit = true, liveVersion = null, cachedVersion = "1.5.0"))
        assertTrue(resolveOverwrite(explicit = true, liveVersion = "2.0.0", cachedVersion = null))
    }

    @Test fun `resolveOverwrite honors explicit false regardless of version`() {
        assertEquals(false, resolveOverwrite(explicit = false, liveVersion = "2.0.0", cachedVersion = null))
        assertEquals(false, resolveOverwrite(explicit = false, liveVersion = null, cachedVersion = "2.0.0"))
    }

    @Test fun `resolveOverwrite defaults to true on Kuma 2 x when explicit is null`() {
        assertTrue(resolveOverwrite(explicit = null, liveVersion = "2.0.0", cachedVersion = null))
        assertTrue(resolveOverwrite(explicit = null, liveVersion = "2.2.1", cachedVersion = null))
    }

    @Test fun `resolveOverwrite defaults to false on Kuma 1 x when explicit is null`() {
        // The bug R2-2 fixed: Kuma 1.x sends heartbeatList without the
        // overwrite arg and historically meant "incremental append." Default
        // must NOT be true or we'd wipe history every connect.
        assertEquals(false, resolveOverwrite(explicit = null, liveVersion = "1.23.16", cachedVersion = null))
        assertEquals(false, resolveOverwrite(explicit = null, liveVersion = "1.0.0", cachedVersion = null))
    }

    @Test fun `resolveOverwrite uses cached version when liveVersion is null`() {
        // The race the cached version closes: heartbeatList lands before info.
        // Without the cached fallback, version is null → default to true →
        // wipes Kuma 1.x history. With cache, the prior connect's version
        // carries us through.
        assertEquals(false, resolveOverwrite(explicit = null, liveVersion = null, cachedVersion = "1.23.0"))
        assertTrue(resolveOverwrite(explicit = null, liveVersion = null, cachedVersion = "2.0.0"))
    }

    @Test fun `resolveOverwrite prefers liveVersion over cachedVersion`() {
        // If a server upgrades from 1.x to 2.x between connects, the cached
        // version is stale. Live wins.
        assertTrue(resolveOverwrite(explicit = null, liveVersion = "2.0.0", cachedVersion = "1.23.0"))
        assertEquals(false, resolveOverwrite(explicit = null, liveVersion = "1.0.0", cachedVersion = "2.0.0"))
    }

    @Test fun `resolveOverwrite defaults to false when both versions unknown`() {
        // K2: Kuma 2.x servers always send the explicit `overwrite` boolean,
        // so reaching this branch (explicit == null AND version unknown) is
        // almost certainly a Kuma 1.x server whose `info` push hasn't
        // arrived yet. Defaulting to overwrite would wipe locally seeded
        // history; safer to append.
        assertEquals(false, resolveOverwrite(explicit = null, liveVersion = null, cachedVersion = null))
    }

    // ---- P3: Monitor.name fallback ----

    @Test fun `parseMonitor uses fallback when name is missing`() {
        // P3 regression: pre-fix `optString("name")` returned `""` for a
        // missing key, surfacing as a blank row in every list. Now we
        // substitute a deterministic placeholder.
        val o = JSONObject("""{"id":1, "type":"http", "active":true}""")
        assertEquals("(unnamed)", KumaJson.parseMonitor(o).name)
    }

    @Test fun `parseMonitor uses fallback when name is empty string`() {
        val o = JSONObject("""{"id":1, "name":"", "type":"http", "active":true}""")
        assertEquals("(unnamed)", KumaJson.parseMonitor(o).name)
    }

    @Test fun `parseMonitor uses fallback when name is the string null`() {
        // optStringOrNull treats the literal string "null" as null, so this
        // also funnels through the fallback path.
        val o = JSONObject("""{"id":1, "name":"null", "type":"http", "active":true}""")
        assertEquals("(unnamed)", KumaJson.parseMonitor(o).name)
    }

    // ---- P4: optDoubleOrNull rejects NaN / Infinity ----

    @Test fun `parseHeartbeat ping string NaN is dropped to null`() {
        // P4 regression: pre-fix `String.toDoubleOrNull()` happily returned
        // NaN, which then poisoned every chart that referenced it (sparkline
        // axis math, avg ping calc). We test the string path because
        // org.json rejects NaN / Infinity in `put(key, double)` — but Kuma
        // can and does serialize ping values as strings on some versions.
        val o = JSONObject("""{"monitorID":1, "status":1, "time":"x", "ping":"NaN"}""")
        assertNull(KumaJson.parseHeartbeat(o).ping)
    }

    @Test fun `parseHeartbeat ping string Infinity is dropped to null`() {
        val o = JSONObject("""{"monitorID":1, "status":1, "time":"x", "ping":"Infinity"}""")
        assertNull(KumaJson.parseHeartbeat(o).ping)
    }

    @Test fun `parseHeartbeat ping finite value passes through`() {
        // Sanity check — finite values still round-trip after the filter.
        val o = JSONObject("""{"monitorID":1, "status":1, "time":"x", "ping":42.5}""")
        assertEquals(42.5, KumaJson.parseHeartbeat(o).ping ?: -1.0, 0.0001)
    }
}
