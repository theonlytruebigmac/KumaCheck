package app.kumacheck.ui.edit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorEditPayloadTest {

    private fun http(
        name: String = "Probe",
        url: String = "https://example.com/health",
        method: String = "GET",
        body: String = "",
        httpBodyEncoding: String = "json",
        acceptedStatusCodes: String = "200-299",
        keyword: String = "",
        jsonPath: String = "",
        expectedValue: String = "",
        httpHeaders: String = "",
        authMethod: String = "",
        basicAuthUser: String = "",
        basicAuthPass: String = "",
        interval: String = "60",
        retryInterval: String = "60",
        maxretries: String = "0",
        resendInterval: String = "0",
        type: String = "http",
    ) = MonitorEditViewModel.Form(
        name = name, type = type, url = url, method = method, body = body,
        httpBodyEncoding = httpBodyEncoding, acceptedStatusCodes = acceptedStatusCodes,
        keyword = keyword, jsonPath = jsonPath, expectedValue = expectedValue,
        httpHeaders = httpHeaders, authMethod = authMethod,
        basicAuthUser = basicAuthUser, basicAuthPass = basicAuthPass,
        interval = interval, retryInterval = retryInterval,
        maxretries = maxretries, resendInterval = resendInterval,
    )

    @Test fun `create http monitor sets type and required defaults`() {
        val out = buildMonitorPayload(JSONObject(), http(), "", isCreate = true)
        assertEquals("http", out.getString("type"))
        assertEquals("Probe", out.getString("name"))
        assertEquals(60, out.getInt("interval"))
        assertEquals(60, out.getInt("retryInterval"))
        assertEquals(0, out.getInt("maxretries"))
        assertEquals(0, out.getInt("resendInterval"))
        assertEquals("https://example.com/health", out.getString("url"))
        assertEquals("GET", out.getString("method"))
        // empty body is *not* added — server lets it default
        assertFalse(out.has("body"))
    }

    @Test fun `name is trimmed before being written`() {
        val out = buildMonitorPayload(JSONObject(), http(name = "  Probe  "), "", isCreate = true)
        assertEquals("Probe", out.getString("name"))
    }

    @Test fun `accepted status codes parse comma-separated input into JSONArray`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(acceptedStatusCodes = "200-299, 404 ,  500"),
            "", isCreate = true,
        )
        val arr = out.getJSONArray("accepted_statuscodes")
        assertEquals(3, arr.length())
        assertEquals("200-299", arr.getString(0))
        assertEquals("404", arr.getString(1))
        assertEquals("500", arr.getString(2))
    }

    @Test fun `accepted status codes default to 200-299 when blank or whitespace only`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(acceptedStatusCodes = "   ,   ,"),
            "", isCreate = true,
        )
        val arr = out.getJSONArray("accepted_statuscodes")
        assertEquals(1, arr.length())
        assertEquals("200-299", arr.getString(0))
    }

    @Test fun `method falls back to GET when blank`() {
        val out = buildMonitorPayload(JSONObject(), http(method = ""), "", isCreate = true)
        assertEquals("GET", out.getString("method"))
    }

    @Test fun `basic auth selection writes credentials`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(authMethod = "basic", basicAuthUser = "alice", basicAuthPass = "hunter2"),
            originalAuthMethod = "",
            isCreate = true,
        )
        assertEquals("basic", out.getString("authMethod"))
        assertEquals("alice", out.getString("basic_auth_user"))
        assertEquals("hunter2", out.getString("basic_auth_pass"))
    }

    @Test fun `clearing auth from basic to none zeroes credentials`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(authMethod = ""),
            originalAuthMethod = "basic",
            isCreate = false,
        )
        assertEquals("", out.getString("authMethod"))
        assertEquals("", out.getString("basic_auth_user"))
        assertEquals("", out.getString("basic_auth_pass"))
    }

    @Test fun `clearing auth from none to none zeroes credentials`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(authMethod = ""),
            originalAuthMethod = "",
            isCreate = false,
        )
        assertEquals("", out.getString("authMethod"))
    }

    // (NTLM-preservation test removed — NTLM is now editable and the
    // "form blank + original ntlm" case explicitly clears the credentials,
    // which is covered by `clearing auth from ntlm to none zeroes ntlm-specific fields`.)

    @Test fun `headers field passes through verbatim`() {
        val headersJson = """{"X-Trace": "yes"}"""
        val out = buildMonitorPayload(
            JSONObject(),
            http(httpHeaders = headersJson),
            "", isCreate = true,
        )
        assertEquals(headersJson, out.getString("headers"))
    }

    @Test fun `keyword type writes keyword field`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(type = "keyword", keyword = "OK"),
            "", isCreate = true,
        )
        assertEquals("OK", out.getString("keyword"))
    }

    @Test fun `json-query type writes jsonPath and expectedValue when non-blank`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(type = "json-query", jsonPath = "$.status", expectedValue = "ok"),
            "", isCreate = true,
        )
        assertEquals("$.status", out.getString("jsonPath"))
        assertEquals("ok", out.getString("expectedValue"))
    }

    @Test fun `json-query type omits empty jsonPath and expectedValue`() {
        val out = buildMonitorPayload(
            JSONObject(),
            http(type = "json-query"),
            "", isCreate = true,
        )
        assertFalse(out.has("jsonPath"))
        assertFalse(out.has("expectedValue"))
    }

    @Test fun `port type writes hostname and integer port`() {
        val form = MonitorEditViewModel.Form(
            name = "PG", type = "port",
            hostname = " db.local ", port = "5432",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("db.local", out.getString("hostname"))
        assertEquals(5432, out.getInt("port"))
    }

    @Test fun `ping type writes hostname and packetSize`() {
        val form = MonitorEditViewModel.Form(
            name = "Ping", type = "ping",
            hostname = "1.1.1.1", packetSize = "56",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("1.1.1.1", out.getString("hostname"))
        assertEquals(56, out.getInt("packetSize"))
    }

    @Test fun `non-http create payloads still seed accepted_statuscodes and notificationIDList`() {
        // Regression: Kuma's server-side `add` handler iterates these
        // arrays via `.every()` regardless of monitor type. A `ping`
        // monitor that omits them server-crashes with "cannot read
        // properties of undefined (reading 'every')". Defaults must be
        // present even when the type-specific block doesn't touch them.
        val form = MonitorEditViewModel.Form(
            name = "Ping", type = "ping",
            hostname = "1.1.1.1",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertTrue(out.has("accepted_statuscodes"))
        val codes = out.getJSONArray("accepted_statuscodes")
        assertEquals(1, codes.length())
        assertEquals("200-299", codes.getString(0))
        assertTrue(out.has("notificationIDList"))
        assertEquals(0, out.getJSONArray("notificationIDList").length())
    }

    @Test fun `port create payload also seeds the universal defaults`() {
        val form = MonitorEditViewModel.Form(
            name = "Port", type = "port",
            hostname = "db.local", port = "5432",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertTrue(out.has("accepted_statuscodes"))
        assertTrue(out.has("notificationIDList"))
    }

    @Test fun `every create payload seeds conditions as an empty array`() {
        // Regression: Kuma 2.x's `monitor.conditions` SQLite column is
        // NOT NULL. The server's `add` handler does
        // `monitor.conditions = JSON.stringify(monitor.conditions)` —
        // undefined → JS undefined → SQL NULL → constraint violation
        // ("NOT NULL constraint failed: monitor.conditions"). Verify
        // every type ships an empty JSON array.
        val types = listOf(
            "http", "keyword", "json-query",
            "ping", "tailscale-ping",
            "port", "dns", "mqtt",
            "grpc-keyword", "kafka-producer",
        )
        for (type in types) {
            val form = MonitorEditViewModel.Form(name = "x", type = type)
            val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
            assertTrue("conditions missing for $type", out.has("conditions"))
            assertEquals(0, out.getJSONArray("conditions").length())
        }
    }

    // Per-type required-field defaults — these prevent server-validated
    // monitors that DOWN forever because a runtime check() unconditionally
    // dereferences a field the user left blank.

    @Test fun `port create defaults port to 80 when blank`() {
        val form = MonitorEditViewModel.Form(
            name = "P", type = "port", hostname = "h.example",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals(80, out.getInt("port"))
    }

    @Test fun `dns create defaults port + resolver type + resolver server`() {
        val form = MonitorEditViewModel.Form(
            name = "D", type = "dns", hostname = "example.com",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals(53, out.getInt("port"))
        assertEquals("A", out.getString("dns_resolve_type"))
        assertEquals("1.1.1.1", out.getString("dns_resolve_server"))
    }

    @Test fun `dns create preserves user-typed resolver values`() {
        val form = MonitorEditViewModel.Form(
            name = "D", type = "dns", hostname = "example.com", port = "853",
            dnsResolveServer = "9.9.9.9", dnsResolveType = "AAAA",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals(853, out.getInt("port"))
        assertEquals("AAAA", out.getString("dns_resolve_type"))
        assertEquals("9.9.9.9", out.getString("dns_resolve_server"))
    }

    @Test fun `mqtt create defaults port to 1883 when blank`() {
        val form = MonitorEditViewModel.Form(
            name = "M", type = "mqtt", hostname = "broker.local", mqttTopic = "/health",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals(1883, out.getInt("port"))
    }

    // ---- validateRequiredTypeFields ----

    private fun fieldsForType(type: String, mut: MonitorEditViewModel.Form.() -> MonitorEditViewModel.Form = { this }) =
        MonitorEditViewModel.Form(name = "x", type = type).mut()

    @Test fun `http requires url`() {
        assertEquals("URL is required", validateRequiredTypeFields(fieldsForType("http")))
        assertNull(validateRequiredTypeFields(fieldsForType("http") { copy(url = "https://h") }))
    }

    @Test fun `keyword requires url AND keyword`() {
        assertEquals("URL is required", validateRequiredTypeFields(fieldsForType("keyword")))
        assertEquals(
            "Keyword is required",
            validateRequiredTypeFields(fieldsForType("keyword") { copy(url = "https://h") }),
        )
        assertNull(
            validateRequiredTypeFields(
                fieldsForType("keyword") { copy(url = "https://h", keyword = "ok") },
            ),
        )
    }

    @Test fun `json-query requires url AND jsonPath`() {
        assertEquals(
            "JSON path is required",
            validateRequiredTypeFields(fieldsForType("json-query") { copy(url = "https://h") }),
        )
    }

    @Test fun `ping requires hostname`() {
        assertEquals("Hostname is required", validateRequiredTypeFields(fieldsForType("ping")))
        assertNull(validateRequiredTypeFields(fieldsForType("ping") { copy(hostname = "1.1.1.1") }))
    }

    @Test fun `tailscale-ping requires hostname`() {
        assertEquals(
            "Hostname is required",
            validateRequiredTypeFields(fieldsForType("tailscale-ping")),
        )
    }

    @Test fun `port requires hostname`() {
        assertEquals("Hostname is required", validateRequiredTypeFields(fieldsForType("port")))
    }

    @Test fun `dns requires hostname`() {
        assertEquals("Hostname is required", validateRequiredTypeFields(fieldsForType("dns")))
    }

    @Test fun `mqtt requires hostname AND topic`() {
        assertEquals("Hostname is required", validateRequiredTypeFields(fieldsForType("mqtt")))
        assertEquals(
            "MQTT topic is required",
            validateRequiredTypeFields(fieldsForType("mqtt") { copy(hostname = "broker.local") }),
        )
    }

    @Test fun `grpc-keyword requires url, service, method, keyword`() {
        assertEquals(
            "gRPC URL is required",
            validateRequiredTypeFields(fieldsForType("grpc-keyword")),
        )
        assertEquals(
            "Service name is required",
            validateRequiredTypeFields(
                fieldsForType("grpc-keyword") { copy(grpcUrl = "grpc://h") },
            ),
        )
        assertEquals(
            "Method is required",
            validateRequiredTypeFields(
                fieldsForType("grpc-keyword") {
                    copy(grpcUrl = "grpc://h", grpcServiceName = "S")
                },
            ),
        )
        assertEquals(
            "Keyword is required",
            validateRequiredTypeFields(
                fieldsForType("grpc-keyword") {
                    copy(grpcUrl = "grpc://h", grpcServiceName = "S", grpcMethod = "M")
                },
            ),
        )
    }

    @Test fun `kafka-producer requires brokers, topic, message`() {
        assertEquals(
            "At least one broker is required",
            validateRequiredTypeFields(fieldsForType("kafka-producer")),
        )
        assertEquals(
            "Topic is required",
            validateRequiredTypeFields(
                fieldsForType("kafka-producer") { copy(kafkaProducerBrokers = "b1:9092") },
            ),
        )
        assertEquals(
            "Message is required",
            validateRequiredTypeFields(
                fieldsForType("kafka-producer") {
                    copy(kafkaProducerBrokers = "b1:9092", kafkaProducerTopic = "t")
                },
            ),
        )
    }

    @Test fun `unknown type defers to server`() {
        assertNull(validateRequiredTypeFields(fieldsForType("rabbitmq")))
    }

    @Test fun `dns type writes resolver and record type`() {
        val form = MonitorEditViewModel.Form(
            name = "DNS", type = "dns",
            hostname = "example.com", port = "53",
            dnsResolveServer = "8.8.8.8", dnsResolveType = "AAAA",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("example.com", out.getString("hostname"))
        assertEquals(53, out.getInt("port"))
        assertEquals("8.8.8.8", out.getString("dns_resolve_server"))
        assertEquals("AAAA", out.getString("dns_resolve_type"))
    }

    @Test fun `unsupported type leaves type-specific fields untouched on edit`() {
        // Edit a postgres monitor — we don't render its fields, raw
        // round-trip should keep them intact and we shouldn't write a stray
        // hostname or other key that doesn't apply.
        val raw = JSONObject()
            .put("databaseConnectionString", "postgresql://u:p@host/db")
            .put("databaseQuery", "SELECT 1")
        val form = MonitorEditViewModel.Form(
            name = "DB probe", type = "postgres",
            hostname = "ignored", description = "pg probe",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        assertEquals("postgresql://u:p@host/db", out.getString("databaseConnectionString"))
        assertEquals("SELECT 1", out.getString("databaseQuery"))
        assertFalse(out.has("hostname"))
        assertEquals("DB probe", out.getString("name"))
        assertEquals("pg probe", out.getString("description"))
    }

    @Test fun `numeric form fields ignore non-numeric input via toIntOrNull`() {
        // The form uses string fields; non-int input is silently dropped
        // rather than overwriting whatever the original raw had.
        val raw = JSONObject().put("interval", 120).put("maxretries", 3)
        val form = http(interval = "abc", maxretries = "")
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        // interval untouched (form value rejected)
        assertEquals(120, out.getInt("interval"))
        assertEquals(3, out.getInt("maxretries"))
    }

    @Test fun `create flow does not overwrite explicit fields already set on the JSON`() {
        // Caller could pre-populate `into` (e.g. with intentional defaults).
        // The create branch should respect them.
        val into = JSONObject().put("interval", 30).put("retryInterval", 30)
        val out = buildMonitorPayload(into, http(interval = "", retryInterval = ""), "", isCreate = true)
        // form interval is blank, so toIntOrNull produces null and we don't
        // overwrite. The pre-set value survives.
        assertEquals(30, out.getInt("interval"))
        assertEquals(30, out.getInt("retryInterval"))
    }

    @Test fun `body and httpBodyEncoding only emit when set`() {
        val emptyBody = buildMonitorPayload(JSONObject(), http(body = "", httpBodyEncoding = ""), "", isCreate = true)
        assertFalse(emptyBody.has("body"))
        assertFalse(emptyBody.has("httpBodyEncoding"))

        val filled = buildMonitorPayload(
            JSONObject(),
            http(body = "{\"k\":\"v\"}", httpBodyEncoding = "json"),
            "", isCreate = true,
        )
        assertEquals("{\"k\":\"v\"}", filled.getString("body"))
        assertEquals("json", filled.getString("httpBodyEncoding"))
    }

    @Test fun `description always written even when blank`() {
        val out = buildMonitorPayload(JSONObject(), http(), "", isCreate = true)
        assertTrue(out.has("description"))
        assertEquals("", out.getString("description"))
    }

    @Test fun `ntlm auth writes domain and workstation alongside basic credentials`() {
        val form = http(
            authMethod = "ntlm",
            basicAuthUser = "alice", basicAuthPass = "hunter2",
        ).copy(authDomain = "EXAMPLE", authWorkstation = "WS-01")
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("ntlm", out.getString("authMethod"))
        assertEquals("alice", out.getString("basic_auth_user"))
        assertEquals("hunter2", out.getString("basic_auth_pass"))
        assertEquals("EXAMPLE", out.getString("authDomain"))
        assertEquals("WS-01", out.getString("authWorkstation"))
    }

    @Test fun `oauth2 client-credentials writes token URL, client id-secret, scopes`() {
        val form = http(authMethod = "oauth2-cc").copy(
            oauthAuthMethod = "client_secret_post",
            oauthTokenUrl = " https://auth.example/oauth2/token ",
            oauthClientId = "abc123",
            oauthClientSecret = "shh",
            oauthScopes = "read write",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("oauth2-cc", out.getString("authMethod"))
        assertEquals("client_secret_post", out.getString("oauth_auth_method"))
        assertEquals("https://auth.example/oauth2/token", out.getString("oauth_token_url"))
        assertEquals("abc123", out.getString("oauth_client_id"))
        assertEquals("shh", out.getString("oauth_client_secret"))
        assertEquals("read write", out.getString("oauth_scopes"))
    }

    @Test fun `oauth2 falls back to client_secret_basic when method blank`() {
        val form = http(authMethod = "oauth2-cc").copy(oauthAuthMethod = "")
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("client_secret_basic", out.getString("oauth_auth_method"))
    }

    @Test fun `mtls writes ca, cert and key fields`() {
        val form = http(authMethod = "mtls").copy(
            tlsCa = "-----BEGIN CERTIFICATE-----CA-----END CERTIFICATE-----",
            tlsCert = "-----BEGIN CERTIFICATE-----CRT-----END CERTIFICATE-----",
            tlsKey = "-----BEGIN PRIVATE KEY-----K-----END PRIVATE KEY-----",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("mtls", out.getString("authMethod"))
        assertTrue(out.getString("tlsCa").startsWith("-----BEGIN CERTIFICATE-----CA"))
        assertTrue(out.getString("tlsCert").startsWith("-----BEGIN CERTIFICATE-----CRT"))
        assertTrue(out.getString("tlsKey").startsWith("-----BEGIN PRIVATE KEY-----K"))
    }

    @Test fun `clearing auth from ntlm to none zeroes ntlm-specific fields`() {
        val raw = JSONObject()
            .put("authMethod", "ntlm")
            .put("basic_auth_user", "u").put("basic_auth_pass", "p")
            .put("authDomain", "EX").put("authWorkstation", "WS")
        val out = buildMonitorPayload(raw, http(authMethod = ""), originalAuthMethod = "ntlm", isCreate = false)
        assertEquals("", out.getString("authMethod"))
        assertEquals("", out.getString("authDomain"))
        assertEquals("", out.getString("authWorkstation"))
        assertEquals("", out.getString("basic_auth_user"))
    }

    @Test fun `clearing auth from oauth2 zeroes oauth fields`() {
        val raw = JSONObject()
            .put("authMethod", "oauth2-cc")
            .put("oauth_token_url", "https://t").put("oauth_client_id", "x")
            .put("oauth_client_secret", "y").put("oauth_scopes", "read")
        val out = buildMonitorPayload(raw, http(authMethod = ""), originalAuthMethod = "oauth2-cc", isCreate = false)
        assertEquals("", out.getString("oauth_token_url"))
        assertEquals("", out.getString("oauth_client_id"))
        assertEquals("", out.getString("oauth_client_secret"))
        assertEquals("", out.getString("oauth_scopes"))
    }

    @Test fun `clearing auth from mtls zeroes tls fields`() {
        val raw = JSONObject()
            .put("authMethod", "mtls")
            .put("tlsCa", "ca").put("tlsCert", "crt").put("tlsKey", "k")
        val out = buildMonitorPayload(raw, http(authMethod = ""), originalAuthMethod = "mtls", isCreate = false)
        assertEquals("", out.getString("tlsCa"))
        assertEquals("", out.getString("tlsCert"))
        assertEquals("", out.getString("tlsKey"))
    }

    @Test fun `unknown future auth method clears authMethod but leaves unknown fields`() {
        // Forwards-compat: a method we don't know about (say "kerberos") —
        // when the user explicitly taps "None", we set authMethod to "" but
        // we leave the kerberos-specific fields alone since we don't know
        // what they are.
        val raw = JSONObject()
            .put("authMethod", "kerberos")
            .put("krb5Principal", "alice@EXAMPLE")
        val out = buildMonitorPayload(raw, http(authMethod = ""), originalAuthMethod = "kerberos", isCreate = false)
        assertEquals("", out.getString("authMethod"))
        assertEquals("alice@EXAMPLE", out.getString("krb5Principal"))
    }

    @Test fun `mqtt type writes broker host, port, topic and credentials`() {
        val form = MonitorEditViewModel.Form(
            name = "Bus", type = "mqtt",
            hostname = "broker.local", port = "1883",
            mqttUsername = "alice", mqttPassword = "shh",
            mqttTopic = "iot/heartbeat", mqttSuccessMessage = "ok",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("mqtt", out.getString("type"))
        assertEquals("broker.local", out.getString("hostname"))
        assertEquals(1883, out.getInt("port"))
        assertEquals("alice", out.getString("mqttUsername"))
        assertEquals("shh", out.getString("mqttPassword"))
        assertEquals("iot/heartbeat", out.getString("mqttTopic"))
        assertEquals("ok", out.getString("mqttSuccessMessage"))
    }

    @Test fun `tailscale-ping type writes hostname only`() {
        val form = MonitorEditViewModel.Form(
            name = "Tailnet", type = "tailscale-ping",
            hostname = "100.64.0.1",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("tailscale-ping", out.getString("type"))
        assertEquals("100.64.0.1", out.getString("hostname"))
        assertFalse(out.has("packetSize"))
    }

    @Test fun `docker type writes container and preserves docker_host FK`() {
        val raw = JSONObject().put("docker_host", 7) // existing FK from web UI
        val form = MonitorEditViewModel.Form(
            name = "Redis", type = "docker", dockerContainer = " redis ",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        assertEquals("redis", out.getString("docker_container"))
        assertEquals(7, out.getInt("docker_host")) // FK untouched
    }

    @Test fun `grpc-keyword writes url, service, method, protobuf, body, metadata, keyword and tls`() {
        val form = MonitorEditViewModel.Form(
            name = "gRPC probe", type = "grpc-keyword",
            grpcUrl = " grpc.example:50051 ",
            grpcServiceName = " MyService ",
            grpcMethod = " Check ",
            grpcProtobuf = "syntax = \"proto3\";",
            grpcBody = "{}",
            grpcMetadata = "{ \"x\": \"y\" }",
            keyword = "ok",
            grpcEnableTls = true,
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("grpc-keyword", out.getString("type"))
        assertEquals("grpc.example:50051", out.getString("grpcUrl"))
        assertEquals("MyService", out.getString("grpcServiceName"))
        assertEquals("Check", out.getString("grpcMethod"))
        assertEquals("syntax = \"proto3\";", out.getString("grpcProtobuf"))
        assertEquals("{}", out.getString("grpcBody"))
        assertEquals("{ \"x\": \"y\" }", out.getString("grpcMetadata"))
        assertEquals("ok", out.getString("keyword"))
        assertTrue(out.getBoolean("grpcEnableTls"))
    }

    @Test fun `grpc-keyword preserves unknown raw fields`() {
        // Some hypothetical Kuma-side field we don't model — it should remain.
        val raw = JSONObject().put("grpcRetries", 3)
        val form = MonitorEditViewModel.Form(
            name = "p", type = "grpc-keyword",
            grpcUrl = "x:1", grpcServiceName = "S", grpcMethod = "M",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        assertEquals(3, out.getInt("grpcRetries"))
    }

    @Test fun `kafka-producer parses comma-separated brokers into JSON array`() {
        val form = MonitorEditViewModel.Form(
            name = "K", type = "kafka-producer",
            kafkaProducerBrokers = "broker1:9092,  broker2:9092 , broker3:9092",
            kafkaProducerTopic = " heartbeats ",
            kafkaProducerMessage = "ping",
            kafkaProducerSsl = true,
            kafkaProducerAllowAutoTopicCreation = false,
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        assertEquals("kafka-producer", out.getString("type"))
        val brokers = out.getJSONArray("kafkaProducerBrokers")
        assertEquals(3, brokers.length())
        assertEquals("broker1:9092", brokers.getString(0))
        assertEquals("broker2:9092", brokers.getString(1))
        assertEquals("broker3:9092", brokers.getString(2))
        assertEquals("heartbeats", out.getString("kafkaProducerTopic"))
        assertEquals("ping", out.getString("kafkaProducerMessage"))
        assertTrue(out.getBoolean("kafkaProducerSsl"))
        assertFalse(out.getBoolean("kafkaProducerAllowAutoTopicCreation"))
    }

    @Test fun `kafka-producer drops empty broker entries`() {
        val form = MonitorEditViewModel.Form(
            name = "K", type = "kafka-producer",
            kafkaProducerBrokers = " ,broker:9092,  , ",
            kafkaProducerTopic = "t",
        )
        val out = buildMonitorPayload(JSONObject(), form, "", isCreate = true)
        val brokers = out.getJSONArray("kafkaProducerBrokers")
        assertEquals(1, brokers.length())
        assertEquals("broker:9092", brokers.getString(0))
    }

    @Test fun `kafka-producer preserves SASL options on round-trip`() {
        val raw = JSONObject().put(
            "kafkaProducerSaslOptions",
            JSONObject().put("mechanism", "SCRAM-SHA-256").put("username", "u"),
        )
        val form = MonitorEditViewModel.Form(
            name = "K", type = "kafka-producer",
            kafkaProducerBrokers = "broker:9092",
            kafkaProducerTopic = "t",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        val sasl = out.getJSONObject("kafkaProducerSaslOptions")
        assertEquals("SCRAM-SHA-256", sasl.getString("mechanism"))
        assertEquals("u", sasl.getString("username"))
    }

    @Test fun `real-browser writes url and preserves remote_browsers_id FK`() {
        // Edit case: existing remote_browsers_id stays on the raw round-trip.
        val raw = JSONObject().put("remote_browsers_id", 4)
        val form = MonitorEditViewModel.Form(
            name = "Login probe", type = "real-browser",
            url = " https://example.com/login ",
            remoteBrowsersId = "4",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        assertEquals("https://example.com/login", out.getString("url"))
        assertEquals(4, out.getInt("remote_browsers_id"))
    }

    @Test fun `real-browser with blank remoteBrowsersId leaves raw FK untouched`() {
        val raw = JSONObject().put("remote_browsers_id", 7)
        val form = MonitorEditViewModel.Form(
            name = "p", type = "real-browser",
            url = "https://example.com",
            remoteBrowsersId = "",
        )
        val out = buildMonitorPayload(raw, form, "", isCreate = false)
        assertEquals(7, out.getInt("remote_browsers_id"))
    }
}
