package app.kumacheck.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kumacheck.data.model.Monitor
import app.kumacheck.data.socket.KumaSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MonitorEditViewModel(
    private val socket: KumaSocket,
    /** Pass `null` to create a new monitor; otherwise edits the existing one. */
    private val monitorId: Int?,
) : ViewModel() {

    /**
     * Form state. Cross-type fields are always shown. Type-specific fields are
     * shown by the UI based on `type` and only the relevant ones are merged
     * into the payload on save — keys irrelevant to the chosen type are left
     * untouched in the raw round-trip.
     */
    data class Form(
        val name: String = "",
        val interval: String = "",
        val retryInterval: String = "",
        val maxretries: String = "",
        val resendInterval: String = "",
        val description: String = "",
        val type: String = "http",
        val url: String = "",
        val hostname: String = "",
        val port: String = "",
        val method: String = "GET",
        val body: String = "",
        val httpBodyEncoding: String = "json",
        val acceptedStatusCodes: String = "200-299",
        val keyword: String = "",
        val jsonPath: String = "",
        val expectedValue: String = "",
        val packetSize: String = "",
        val dnsResolveServer: String = "1.1.1.1",
        val dnsResolveType: String = "A",
        // HTTP-only: free-form headers (JSON string typed by the user; server
        // stores as text and parses on use) and per-method auth credentials.
        val httpHeaders: String = "",
        val authMethod: String = "",
        // Basic + NTLM share user / pass.
        val basicAuthUser: String = "",
        val basicAuthPass: String = "",
        // NTLM extras.
        val authDomain: String = "",
        val authWorkstation: String = "",
        // OAuth2 client-credentials flow.
        val oauthAuthMethod: String = "client_secret_basic",
        val oauthTokenUrl: String = "",
        val oauthClientId: String = "",
        val oauthClientSecret: String = "",
        val oauthScopes: String = "",
        // mTLS (PEM-encoded).
        val tlsCa: String = "",
        val tlsCert: String = "",
        val tlsKey: String = "",
        // MQTT
        val mqttUsername: String = "",
        val mqttPassword: String = "",
        val mqttTopic: String = "",
        val mqttSuccessMessage: String = "",
        // Docker
        val dockerContainer: String = "",
        // gRPC
        val grpcUrl: String = "",
        val grpcServiceName: String = "",
        val grpcMethod: String = "",
        val grpcProtobuf: String = "",
        val grpcBody: String = "",
        val grpcMetadata: String = "",
        val grpcEnableTls: Boolean = false,
        // Kafka producer
        val kafkaProducerBrokers: String = "",
        val kafkaProducerTopic: String = "",
        val kafkaProducerMessage: String = "",
        val kafkaProducerSsl: Boolean = false,
        val kafkaProducerAllowAutoTopicCreation: Boolean = false,
        // Real browser
        val remoteBrowsersId: String = "", // FK as string for form-input ergonomics; "" = not set
    )

    data class UiState(
        val isCreate: Boolean = false,
        val monitor: Monitor? = null,
        val form: Form = Form(),
        val original: Form = Form(),
        val isSaving: Boolean = false,
        val error: String? = null,
        val saved: Boolean = false,
        /** Set after a successful create — caller can route to this id. */
        val createdId: Int? = null,
    )

    private val _state = MutableStateFlow(UiState(isCreate = monitorId == null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (monitorId != null) {
            viewModelScope.launch {
                // First push wins for `original` — the server snapshot at
                // entry, used by [save] to compute deltas. We don't re-derive
                // `original` on later pushes because that would shift the
                // user's baseline mid-edit (e.g. flipping originalAuthMethod
                // under their feet).
                val firstMap = socket.monitors.first { it.containsKey(monitorId) }
                val firstM = firstMap[monitorId] ?: return@launch
                val raw = socket.monitorsRaw.value[monitorId]
                val initial = formFromRaw(firstM, raw)
                _state.update { it.copy(monitor = firstM, form = initial, original = initial) }
                // Then track live updates to the `monitor` reference only —
                // form / original stay frozen at entry.
                socket.monitors.collect { map ->
                    val m = map[monitorId] ?: return@collect
                    _state.update { it.copy(monitor = m) }
                }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(form = it.form.copy(name = v), error = null) }
    fun onInterval(v: String) = _state.update {
        it.copy(form = it.form.copy(interval = digitsCapped(v, MAX_DIGITS_DURATION)), error = null)
    }
    fun onRetryInterval(v: String) = _state.update {
        it.copy(form = it.form.copy(retryInterval = digitsCapped(v, MAX_DIGITS_DURATION)), error = null)
    }
    fun onMaxRetries(v: String) = _state.update {
        it.copy(form = it.form.copy(maxretries = digitsCapped(v, MAX_DIGITS_RETRIES)), error = null)
    }
    fun onResendInterval(v: String) = _state.update {
        it.copy(form = it.form.copy(resendInterval = digitsCapped(v, MAX_DIGITS_DURATION)), error = null)
    }
    fun onDescription(v: String) = _state.update {
        it.copy(form = it.form.copy(description = v), error = null)
    }
    fun onType(v: String) = _state.update {
        it.copy(form = it.form.copy(type = v), error = null)
    }
    fun onUrl(v: String) = _state.update { it.copy(form = it.form.copy(url = v), error = null) }
    fun onHostname(v: String) = _state.update { it.copy(form = it.form.copy(hostname = v), error = null) }
    fun onPort(v: String) = _state.update {
        it.copy(form = it.form.copy(port = digitsCapped(v, MAX_DIGITS_PORT)), error = null)
    }
    fun onMethod(v: String) = _state.update { it.copy(form = it.form.copy(method = v), error = null) }
    fun onBody(v: String) = _state.update { it.copy(form = it.form.copy(body = v), error = null) }
    fun onHttpBodyEncoding(v: String) = _state.update {
        it.copy(form = it.form.copy(httpBodyEncoding = v), error = null)
    }
    fun onAcceptedStatusCodes(v: String) = _state.update {
        it.copy(form = it.form.copy(acceptedStatusCodes = v), error = null)
    }
    fun onKeyword(v: String) = _state.update { it.copy(form = it.form.copy(keyword = v), error = null) }
    fun onJsonPath(v: String) = _state.update { it.copy(form = it.form.copy(jsonPath = v), error = null) }
    fun onExpectedValue(v: String) = _state.update {
        it.copy(form = it.form.copy(expectedValue = v), error = null)
    }
    fun onPacketSize(v: String) = _state.update {
        it.copy(form = it.form.copy(packetSize = digitsCapped(v, MAX_DIGITS_PACKET_SIZE)), error = null)
    }
    fun onDnsResolveServer(v: String) = _state.update {
        it.copy(form = it.form.copy(dnsResolveServer = v), error = null)
    }
    fun onDnsResolveType(v: String) = _state.update {
        it.copy(form = it.form.copy(dnsResolveType = v), error = null)
    }
    fun onHttpHeaders(v: String) = _state.update {
        it.copy(form = it.form.copy(httpHeaders = v), error = null)
    }
    fun onAuthMethod(v: String) = _state.update {
        it.copy(form = it.form.copy(authMethod = v), error = null)
    }
    fun onBasicAuthUser(v: String) = _state.update {
        it.copy(form = it.form.copy(basicAuthUser = v), error = null)
    }
    fun onBasicAuthPass(v: String) = _state.update {
        it.copy(form = it.form.copy(basicAuthPass = v), error = null)
    }
    fun onAuthDomain(v: String) = _state.update {
        it.copy(form = it.form.copy(authDomain = v), error = null)
    }
    fun onAuthWorkstation(v: String) = _state.update {
        it.copy(form = it.form.copy(authWorkstation = v), error = null)
    }
    fun onOauthAuthMethod(v: String) = _state.update {
        it.copy(form = it.form.copy(oauthAuthMethod = v), error = null)
    }
    fun onOauthTokenUrl(v: String) = _state.update {
        it.copy(form = it.form.copy(oauthTokenUrl = v), error = null)
    }
    fun onOauthClientId(v: String) = _state.update {
        it.copy(form = it.form.copy(oauthClientId = v), error = null)
    }
    fun onOauthClientSecret(v: String) = _state.update {
        it.copy(form = it.form.copy(oauthClientSecret = v), error = null)
    }
    fun onOauthScopes(v: String) = _state.update {
        it.copy(form = it.form.copy(oauthScopes = v), error = null)
    }
    fun onTlsCa(v: String) = _state.update { it.copy(form = it.form.copy(tlsCa = v), error = null) }
    fun onTlsCert(v: String) = _state.update { it.copy(form = it.form.copy(tlsCert = v), error = null) }
    fun onTlsKey(v: String) = _state.update { it.copy(form = it.form.copy(tlsKey = v), error = null) }
    fun onMqttUsername(v: String) = _state.update {
        it.copy(form = it.form.copy(mqttUsername = v), error = null)
    }
    fun onMqttPassword(v: String) = _state.update {
        it.copy(form = it.form.copy(mqttPassword = v), error = null)
    }
    fun onMqttTopic(v: String) = _state.update {
        it.copy(form = it.form.copy(mqttTopic = v), error = null)
    }
    fun onMqttSuccessMessage(v: String) = _state.update {
        it.copy(form = it.form.copy(mqttSuccessMessage = v), error = null)
    }
    fun onDockerContainer(v: String) = _state.update {
        it.copy(form = it.form.copy(dockerContainer = v), error = null)
    }
    fun onGrpcUrl(v: String) = _state.update { it.copy(form = it.form.copy(grpcUrl = v), error = null) }
    fun onGrpcServiceName(v: String) = _state.update {
        it.copy(form = it.form.copy(grpcServiceName = v), error = null)
    }
    fun onGrpcMethod(v: String) = _state.update {
        it.copy(form = it.form.copy(grpcMethod = v), error = null)
    }
    fun onGrpcProtobuf(v: String) = _state.update {
        it.copy(form = it.form.copy(grpcProtobuf = v), error = null)
    }
    fun onGrpcBody(v: String) = _state.update {
        it.copy(form = it.form.copy(grpcBody = v), error = null)
    }
    fun onGrpcMetadata(v: String) = _state.update {
        it.copy(form = it.form.copy(grpcMetadata = v), error = null)
    }
    fun onGrpcEnableTls(v: Boolean) = _state.update {
        it.copy(form = it.form.copy(grpcEnableTls = v), error = null)
    }
    fun onKafkaBrokers(v: String) = _state.update {
        it.copy(form = it.form.copy(kafkaProducerBrokers = v), error = null)
    }
    fun onKafkaTopic(v: String) = _state.update {
        it.copy(form = it.form.copy(kafkaProducerTopic = v), error = null)
    }
    fun onKafkaMessage(v: String) = _state.update {
        it.copy(form = it.form.copy(kafkaProducerMessage = v), error = null)
    }
    fun onKafkaSsl(v: Boolean) = _state.update {
        it.copy(form = it.form.copy(kafkaProducerSsl = v), error = null)
    }
    fun onKafkaAllowAutoTopicCreation(v: Boolean) = _state.update {
        it.copy(form = it.form.copy(kafkaProducerAllowAutoTopicCreation = v), error = null)
    }

    fun save() {
        val cur = _state.value
        if (cur.isSaving) return
        val isCreate = cur.isCreate
        if (!isCreate && cur.monitor == null) {
            _state.update { it.copy(error = "Monitor not loaded yet") }
            return
        }

        val raw = if (isCreate) {
            JSONObject()
        } else {
            socket.monitorsRaw.value[monitorId]?.let { JSONObject(it.toString()) } ?: run {
                _state.update { it.copy(error = "Original monitor data unavailable") }
                return
            }
        }

        if (cur.form.name.isBlank()) {
            _state.update { it.copy(error = "Name is required") }
            return
        }
        // For HTTP-style monitors only, the headers + status-codes fields are
        // user-typed strings round-tripped to the server. Validate at save time
        // so the user gets a clear error here instead of an opaque server-side
        // failure later. Empty strings are skipped — they're allowed.
        if (cur.form.type == "http" || cur.form.type == "keyword" || cur.form.type == "json-query") {
            val headersErr = validateHeadersJson(cur.form.httpHeaders)
            if (headersErr != null) {
                _state.update { it.copy(error = headersErr) }
                return
            }
            val codesErr = validateAcceptedStatusCodes(cur.form.acceptedStatusCodes)
            if (codesErr != null) {
                _state.update { it.copy(error = codesErr) }
                return
            }
        }
        buildMonitorPayload(
            into = raw,
            form = cur.form,
            originalAuthMethod = cur.original.authMethod,
            isCreate = isCreate,
        )

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                if (isCreate) {
                    val (ok, newId, msg) = socket.addMonitor(raw)
                    if (ok) {
                        _state.update { it.copy(isSaving = false, saved = true, createdId = newId) }
                    } else {
                        _state.update { it.copy(isSaving = false, error = msg ?: "Server rejected the new monitor") }
                    }
                } else {
                    val (ok, msg) = socket.editMonitor(raw)
                    if (ok) {
                        _state.update { it.copy(isSaving = false, saved = true) }
                    } else {
                        _state.update { it.copy(isSaving = false, error = msg ?: "Server rejected the change") }
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSaving = false, error = t.message ?: "unknown error") }
            }
        }
    }


    private fun formFromRaw(m: Monitor, raw: JSONObject?): Form {
        fun str(key: String, default: String = ""): String {
            if (raw == null) return default
            return when {
                !raw.has(key) || raw.isNull(key) -> default
                else -> when (val v = raw.get(key)) {
                    is String -> v
                    is Number -> v.toString()
                    else -> default
                }
            }
        }
        fun intStr(key: String): String {
            if (raw == null) return ""
            return when {
                !raw.has(key) || raw.isNull(key) -> ""
                else -> when (val v = raw.get(key)) {
                    is Number -> v.toInt().toString()
                    is String -> v
                    else -> ""
                }
            }
        }
        val statuses = raw?.optJSONArray("accepted_statuscodes")
        val statusesStr = if (statuses != null) {
            (0 until statuses.length()).joinToString(", ") { statuses.optString(it) }
        } else "200-299"
        return Form(
            name = m.name,
            interval = intStr("interval"),
            retryInterval = intStr("retryInterval"),
            maxretries = intStr("maxretries"),
            resendInterval = intStr("resendInterval"),
            description = str("description"),
            type = m.type,
            url = m.url ?: str("url"),
            hostname = m.hostname ?: str("hostname"),
            port = m.port?.toString() ?: intStr("port"),
            method = str("method", "GET"),
            body = str("body"),
            httpBodyEncoding = str("httpBodyEncoding", "json"),
            acceptedStatusCodes = statusesStr,
            keyword = str("keyword"),
            jsonPath = str("jsonPath"),
            expectedValue = str("expectedValue"),
            packetSize = intStr("packetSize"),
            dnsResolveServer = str("dns_resolve_server", "1.1.1.1"),
            dnsResolveType = str("dns_resolve_type", "A"),
            httpHeaders = str("headers"),
            authMethod = str("authMethod"),
            basicAuthUser = str("basic_auth_user"),
            basicAuthPass = str("basic_auth_pass"),
            authDomain = str("authDomain"),
            authWorkstation = str("authWorkstation"),
            oauthAuthMethod = str("oauth_auth_method", "client_secret_basic"),
            oauthTokenUrl = str("oauth_token_url"),
            oauthClientId = str("oauth_client_id"),
            oauthClientSecret = str("oauth_client_secret"),
            oauthScopes = str("oauth_scopes"),
            tlsCa = str("tlsCa"),
            tlsCert = str("tlsCert"),
            tlsKey = str("tlsKey"),
            mqttUsername = str("mqttUsername"),
            mqttPassword = str("mqttPassword"),
            mqttTopic = str("mqttTopic"),
            mqttSuccessMessage = str("mqttSuccessMessage"),
            dockerContainer = str("docker_container"),
            grpcUrl = str("grpcUrl"),
            grpcServiceName = str("grpcServiceName"),
            grpcMethod = str("grpcMethod"),
            grpcProtobuf = str("grpcProtobuf"),
            grpcBody = str("grpcBody"),
            grpcMetadata = str("grpcMetadata"),
            grpcEnableTls = raw?.optBoolean("grpcEnableTls", false) == true,
            kafkaProducerBrokers = run {
                val arr = raw?.optJSONArray("kafkaProducerBrokers")
                if (arr != null) (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                else str("kafkaProducerBrokers")
            },
            kafkaProducerTopic = str("kafkaProducerTopic"),
            kafkaProducerMessage = str("kafkaProducerMessage"),
            kafkaProducerSsl = raw?.optBoolean("kafkaProducerSsl", false) == true,
            kafkaProducerAllowAutoTopicCreation = raw?.optBoolean("kafkaProducerAllowAutoTopicCreation", false) == true,
            remoteBrowsersId = intStr("remote_browsers_id"),
        )
    }

    companion object {
        val SUPPORTED_CREATE_TYPES = listOf(
            "http" to "HTTP(s)",
            "keyword" to "HTTP(s) — Keyword",
            "json-query" to "HTTP(s) — JSON Query",
            "ping" to "Ping",
            "port" to "TCP Port",
            "dns" to "DNS",
            "tailscale-ping" to "Tailscale Ping",
            "mqtt" to "MQTT",
            "grpc-keyword" to "gRPC — Keyword",
            "kafka-producer" to "Kafka Producer",
        )

        /**
         * Digit-only filter with a per-field character cap. The cap stops a
         * paste of "9999999999" from overflowing the field's domain at save
         * time — without it, the value either silently overflows Int.MAX_VALUE
         * (`toIntOrNull` returns null and the field is omitted from the
         * payload) or saves a value the server then rejects opaquely.
         *
         * Per-field maxes match each field's natural upper bound:
         *  - port / packet size: 5 digits (65535)
         *  - retries: 3 digits (~999, generous; Kuma defaults are <10)
         *  - durations (interval, retry, resend): 7 digits (over 11 days in seconds)
         */
        private const val MAX_DIGITS_PORT = 5
        private const val MAX_DIGITS_PACKET_SIZE = 5
        private const val MAX_DIGITS_RETRIES = 3
        private const val MAX_DIGITS_DURATION = 7
        private fun digitsCapped(v: String, maxDigits: Int): String =
            v.filter { it.isDigit() }.take(maxDigits)
    }
}

/**
 * Pure form-to-JSON merge. Mutates [into] (caller passes either an empty object
 * for create, or a clone of the raw round-trip blob for edit). Lives at the top
 * level so unit tests can call it without a real socket harness.
 */
internal fun buildMonitorPayload(
    into: JSONObject,
    form: MonitorEditViewModel.Form,
    originalAuthMethod: String,
    isCreate: Boolean,
): JSONObject {
    into.put("name", form.name.trim())

    if (isCreate) {
        into.put("type", form.type)
        // Sensible defaults that Kuma rejects when missing.
        if (!into.has("interval")) into.put("interval", 60)
        if (!into.has("retryInterval")) into.put("retryInterval", 60)
        if (!into.has("maxretries")) into.put("maxretries", 0)
        if (!into.has("resendInterval")) into.put("resendInterval", 0)
    }
    form.interval.toIntOrNull()?.let { into.put("interval", it) }
    form.retryInterval.toIntOrNull()?.let { into.put("retryInterval", it) }
    form.maxretries.toIntOrNull()?.let { into.put("maxretries", it) }
    form.resendInterval.toIntOrNull()?.let { into.put("resendInterval", it) }
    into.put("description", form.description)

    when (form.type) {
        "http", "keyword", "json-query" -> {
            into.put("url", form.url.trim())
            into.put("method", form.method.ifBlank { "GET" })
            if (form.body.isNotBlank()) into.put("body", form.body)
            if (form.httpBodyEncoding.isNotBlank()) {
                into.put("httpBodyEncoding", form.httpBodyEncoding)
            }
            into.put("accepted_statuscodes", parseAcceptedStatusCodes(form.acceptedStatusCodes))
            // Headers: server expects a JSON string. Empty string means no
            // headers. Pass through as-is — parse errors surface server-side.
            into.put("headers", form.httpHeaders)
            // Auth: basic / ntlm / oauth2-cc / mtls all editable on mobile.
            // Switching between methods clears the credentials of the previous
            // one so the server isn't left with stale fields from a different
            // mode. Empty `authMethod` clears all auth.
            when (form.authMethod) {
                "basic" -> {
                    into.put("authMethod", "basic")
                    into.put("basic_auth_user", form.basicAuthUser)
                    into.put("basic_auth_pass", form.basicAuthPass)
                }
                "ntlm" -> {
                    into.put("authMethod", "ntlm")
                    into.put("basic_auth_user", form.basicAuthUser)
                    into.put("basic_auth_pass", form.basicAuthPass)
                    into.put("authDomain", form.authDomain)
                    into.put("authWorkstation", form.authWorkstation)
                }
                "oauth2-cc" -> {
                    into.put("authMethod", "oauth2-cc")
                    into.put("oauth_auth_method", form.oauthAuthMethod.ifBlank { "client_secret_basic" })
                    into.put("oauth_token_url", form.oauthTokenUrl.trim())
                    into.put("oauth_client_id", form.oauthClientId)
                    into.put("oauth_client_secret", form.oauthClientSecret)
                    into.put("oauth_scopes", form.oauthScopes)
                }
                "mtls" -> {
                    into.put("authMethod", "mtls")
                    into.put("tlsCa", form.tlsCa)
                    into.put("tlsCert", form.tlsCert)
                    into.put("tlsKey", form.tlsKey)
                }
                "" -> {
                    into.put("authMethod", "")
                    // Clear credentials only for methods we know about — leave
                    // an unknown method's fields alone so we don't corrupt it.
                    if (originalAuthMethod in KNOWN_AUTH_METHODS) {
                        into.put("basic_auth_user", "")
                        into.put("basic_auth_pass", "")
                        into.put("authDomain", "")
                        into.put("authWorkstation", "")
                        into.put("oauth_token_url", "")
                        into.put("oauth_client_id", "")
                        into.put("oauth_client_secret", "")
                        into.put("oauth_scopes", "")
                        into.put("tlsCa", "")
                        into.put("tlsCert", "")
                        into.put("tlsKey", "")
                    }
                }
                else -> {
                    // Unknown method (some future Kuma version) — round-trip
                    // via raw, don't touch.
                }
            }
            if (form.type == "keyword") {
                into.put("keyword", form.keyword)
            }
            if (form.type == "json-query") {
                if (form.jsonPath.isNotBlank()) into.put("jsonPath", form.jsonPath)
                if (form.expectedValue.isNotBlank()) into.put("expectedValue", form.expectedValue)
            }
        }
        "port", "steam", "gamedig" -> {
            into.put("hostname", form.hostname.trim())
            form.port.toIntOrNull()?.let { into.put("port", it) }
        }
        "ping", "tailscale-ping" -> {
            into.put("hostname", form.hostname.trim())
            form.packetSize.toIntOrNull()?.let { into.put("packetSize", it) }
        }
        "dns" -> {
            into.put("hostname", form.hostname.trim())
            form.port.toIntOrNull()?.let { into.put("port", it) }
            if (form.dnsResolveServer.isNotBlank()) {
                into.put("dns_resolve_server", form.dnsResolveServer.trim())
            }
            if (form.dnsResolveType.isNotBlank()) {
                into.put("dns_resolve_type", form.dnsResolveType)
            }
        }
        "mqtt" -> {
            into.put("hostname", form.hostname.trim())
            form.port.toIntOrNull()?.let { into.put("port", it) }
            into.put("mqttUsername", form.mqttUsername)
            into.put("mqttPassword", form.mqttPassword)
            into.put("mqttTopic", form.mqttTopic)
            into.put("mqttSuccessMessage", form.mqttSuccessMessage)
        }
        "docker" -> {
            // docker_host is a FK to a separately-managed Docker host record;
            // the picker is web-UI-only. We round-trip its existing value
            // (already in `into` for edit, absent for create — but `docker`
            // isn't in SUPPORTED_CREATE_TYPES, so create won't reach here).
            into.put("docker_container", form.dockerContainer.trim())
        }
        "grpc-keyword" -> {
            into.put("grpcUrl", form.grpcUrl.trim())
            into.put("grpcServiceName", form.grpcServiceName.trim())
            into.put("grpcMethod", form.grpcMethod.trim())
            into.put("grpcProtobuf", form.grpcProtobuf)
            into.put("grpcBody", form.grpcBody)
            into.put("grpcMetadata", form.grpcMetadata)
            into.put("grpcEnableTls", form.grpcEnableTls)
            into.put("keyword", form.keyword)
        }
        "kafka-producer" -> {
            // Brokers stored as a JSON array of strings; user types as
            // comma-separated for ergonomics.
            val brokers = form.kafkaProducerBrokers
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            into.put("kafkaProducerBrokers", JSONArray(brokers))
            into.put("kafkaProducerTopic", form.kafkaProducerTopic.trim())
            into.put("kafkaProducerMessage", form.kafkaProducerMessage)
            into.put("kafkaProducerSsl", form.kafkaProducerSsl)
            into.put("kafkaProducerAllowAutoTopicCreation", form.kafkaProducerAllowAutoTopicCreation)
            // SASL options are a complex nested object (mechanism + optional
            // creds) that round-trips via raw — too many shapes to model here.
        }
        "real-browser" -> {
            // remote_browsers_id is a FK to a Browser host record (managed in
            // the web UI). Round-trip the existing FK and let the user edit
            // the URL.
            into.put("url", form.url.trim())
            form.remoteBrowsersId.toIntOrNull()?.let { into.put("remote_browsers_id", it) }
        }
        // Other types: cross-type fields only; type-specific fields stay
        // whatever the raw round-trip already had (edit) or empty (create —
        // user will need the web UI for the long tail).
    }
    return into
}

private val KNOWN_AUTH_METHODS = setOf("", "basic", "ntlm", "oauth2-cc", "mtls")

private fun parseAcceptedStatusCodes(input: String): JSONArray {
    val arr = JSONArray()
    input.split(',').forEach { piece ->
        val trimmed = piece.trim()
        if (trimmed.isNotEmpty()) arr.put(trimmed)
    }
    if (arr.length() == 0) arr.put("200-299")
    return arr
}

/**
 * Validate that [input] is either empty or parses as a JSON object whose
 * values are all strings (HTTP header value shape). Returns null on
 * success, or a user-facing error message otherwise.
 */
private fun validateHeadersJson(input: String): String? {
    if (input.isBlank()) return null
    val obj = runCatching { JSONObject(input) }.getOrNull()
        ?: return "HTTP headers must be a JSON object, e.g. {\"X-Api-Key\": \"...\"}"
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next() as String
        val v = obj.opt(k)
        if (v !is String) return "HTTP header \"$k\" must be a string value"
    }
    return null
}

/**
 * Validate accepted status codes — comma-separated list of HTTP statuses or
 * ranges like `200-299`. Each piece must be a 3-digit number or a 3-digit
 * range. Returns null on success, or a user-facing error message otherwise.
 */
private fun validateAcceptedStatusCodes(input: String): String? {
    if (input.isBlank()) return null
    val pieceRegex = Regex("^([1-5]\\d{2})(-[1-5]\\d{2})?$")
    input.split(',').forEach { piece ->
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return@forEach
        if (!pieceRegex.matches(trimmed)) {
            return "Accepted status codes must be 3-digit values or ranges (e.g. 200, 200-299)"
        }
    }
    return null
}
