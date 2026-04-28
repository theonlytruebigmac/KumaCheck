package app.kumacheck.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorEditScreen(
    vm: MonitorEditViewModel,
    onBack: () -> Unit,
    onCreated: ((Int) -> Unit)? = null,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // UX3 navigate-on-save behaviour:
    //  - **Create**: navigate immediately to the new monitor's detail screen.
    //    The detail screen *is* the success signal — popping up a snackbar
    //    here would block the suspend point inside `showSnackbar(...)` for
    //    ~4 s before `onCreated(...)` ran, leaving the user staring at the
    //    edit form.
    //  - **Edit**: show "Saved" briefly, then navigate back. We're returning
    //    to the same detail screen the user came from, so the snackbar is
    //    the only confirmation they get that the change landed.
    LaunchedEffect(ui.saved, ui.createdId) {
        if (!ui.saved) return@LaunchedEffect
        val newId = ui.createdId
        if (ui.isCreate && newId != null && onCreated != null) {
            onCreated(newId)
        } else {
            snackbarHostState.showSnackbar("Saved")
            onBack()
        }
    }

    Scaffold(
        containerColor = KumaCream,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Cancel | Title | Save toolbar matching the asset-pack mockup.
            // statusBarsPadding pushes us below the status-bar inset because we
            // hand-rolled the toolbar instead of using TopAppBar (which would
            // have consumed the inset for us).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        "Cancel",
                        color = KumaSlate,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.bodyEmphasis,
                    )
                }
                Text(
                    if (ui.isCreate) "New monitor" else "Edit monitor",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    onClick = vm::save,
                    enabled = !ui.isSaving && (ui.isCreate || ui.monitor != null),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    if (ui.isSaving) {
                        CircularProgressIndicator(
                            color = KumaTerra, strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            "Save",
                            color = KumaTerra,
                            fontFamily = KumaFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KumaTypography.bodyEmphasis,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (!ui.isCreate && ui.monitor == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = KumaSlate2, fontFamily = KumaFont)
            }
            return@Scaffold
        }
        LazyColumn(
            // UX4: `imePadding()` so the focused field stays above the IME on
            // small phones. Without this the keyboard could cover the bottom
            // form rows and the user has to manually scroll past it.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.error != null) {
                item {
                    KumaAccentCard(accent = KumaDown) {
                        Text(
                            ui.error!!,
                            color = KumaDown,
                            fontFamily = KumaFont,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            if (ui.isCreate) {
                item { KumaSectionHeader("Type") }
                item { TypeGrid(ui.form.type, vm::onType) }
            }
            item { KumaSectionHeader("Name") }
            item {
                KumaInputField(
                    value = ui.form.name,
                    onChange = vm::onName,
                    placeholder = "API · production",
                )
            }
            typeSpecificFields(ui, vm)
            item { KumaSectionHeader("Interval") }
            item {
                IntervalChips(
                    seconds = ui.form.interval.toIntOrNull() ?: 60,
                    onSelect = { vm.onInterval(it.toString()) },
                )
            }
            item { KumaSectionHeader("Description") }
            item {
                KumaInputField(
                    value = ui.form.description,
                    onChange = vm::onDescription,
                    placeholder = "Optional notes",
                    singleLine = false,
                )
            }
            item { KumaSectionHeader("Schedule") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledStatField("Retry (s)", ui.form.retryInterval, vm::onRetryInterval, Modifier.weight(1f))
                    LabeledStatField("Max retries", ui.form.maxretries, vm::onMaxRetries, Modifier.weight(1f))
                    LabeledStatField("Resend after", ui.form.resendInterval, vm::onResendInterval, Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TypeGrid(selected: String, onSelect: (String) -> Unit) {
    val rows = MonitorEditViewModel.SUPPORTED_CREATE_TYPES.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label) ->
                    val isSel = key == selected
                    val short = monitorTypeShort(key)
                    val sub = monitorTypeSubtitle(key)
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) KumaInk else KumaSurface,
                        border = if (isSel) null else BorderStroke(1.dp, KumaCardBorder),
                        onClick = { onSelect(key) },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text(
                                short,
                                color = if (isSel) KumaCream else KumaInk,
                                fontFamily = KumaMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = KumaTypography.body,
                            )
                            Text(
                                sub,
                                color = if (isSel) KumaCream.copy(alpha = 0.7f) else KumaSlate2,
                                fontFamily = KumaMono,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun monitorTypeShort(key: String): String = when (key) {
    "http" -> "HTTPS"
    "keyword" -> "Keyword"
    "json-query" -> "JSON Query"
    "ping" -> "PING"
    "port" -> "TCP"
    "dns" -> "DNS"
    "tailscale-ping" -> "Tailscale"
    "mqtt" -> "MQTT"
    "grpc-keyword" -> "gRPC"
    "kafka-producer" -> "Kafka"
    else -> key.uppercase()
}

private fun monitorTypeSubtitle(key: String): String = when (key) {
    "http" -> "Web endpoint"
    "keyword" -> "Page contains"
    "json-query" -> "JSON path"
    "ping" -> "ICMP echo"
    "port" -> "Port check"
    "dns" -> "Record lookup"
    "tailscale-ping" -> "Tailscale ping"
    "mqtt" -> "Broker subscribe"
    "grpc-keyword" -> "Method + body"
    "kafka-producer" -> "Topic produce"
    else -> ""
}

private fun LazyListScope.typeSpecificFields(
    ui: MonitorEditViewModel.UiState,
    vm: MonitorEditViewModel,
) {
    val type = ui.form.type
    when (type) {
        "http", "keyword", "json-query" -> {
            item { KumaSectionHeader("URL") }
            item {
                KumaInputField(
                    value = ui.form.url,
                    onChange = vm::onUrl,
                    placeholder = "https://api.example.com/health",
                    keyboardType = KeyboardType.Uri,
                    monospace = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Method")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(value = ui.form.method, onChange = vm::onMethod, placeholder = "GET")
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Body encoding")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(
                                value = ui.form.httpBodyEncoding,
                                onChange = vm::onHttpBodyEncoding,
                                placeholder = "json",
                            )
                        }
                    }
                }
            }
            item { KumaSectionHeader("Body") }
            item {
                KumaInputField(
                    value = ui.form.body,
                    onChange = vm::onBody,
                    placeholder = "(optional)",
                    singleLine = false,
                )
            }
            item { KumaSectionHeader("Accepted statuscodes") }
            item {
                KumaInputField(
                    value = ui.form.acceptedStatusCodes,
                    onChange = vm::onAcceptedStatusCodes,
                    placeholder = "200-299, 301",
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Headers") }
            item {
                KumaInputField(
                    value = ui.form.httpHeaders,
                    onChange = vm::onHttpHeaders,
                    placeholder = "{ \"X-Api-Key\": \"…\" }",
                    monospace = true,
                    singleLine = false,
                )
            }
            item { KumaSectionHeader("Authentication") }
            item { AuthMethodChips(ui.form.authMethod, vm::onAuthMethod) }
            authMethodFields(ui, vm)
            if (type == "keyword") {
                item { KumaSectionHeader("Keyword") }
                item {
                    KumaInputField(value = ui.form.keyword, onChange = vm::onKeyword)
                }
            }
            if (type == "json-query") {
                item { KumaSectionHeader("JSON path") }
                item {
                    KumaInputField(
                        value = ui.form.jsonPath,
                        onChange = vm::onJsonPath,
                        placeholder = "data.status",
                        monospace = true,
                    )
                }
                item { KumaSectionHeader("Expected value") }
                item {
                    KumaInputField(value = ui.form.expectedValue, onChange = vm::onExpectedValue)
                }
            }
        }
        "port", "steam", "gamedig" -> {
            item { KumaSectionHeader("Hostname") }
            item {
                KumaInputField(
                    value = ui.form.hostname,
                    onChange = vm::onHostname,
                    placeholder = "host.example.com",
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Port") }
            item {
                KumaInputField(
                    value = ui.form.port,
                    onChange = vm::onPort,
                    placeholder = "443",
                    keyboardType = KeyboardType.Number,
                    monospace = true,
                )
            }
        }
        "ping", "tailscale-ping" -> {
            item { KumaSectionHeader("Hostname") }
            item {
                KumaInputField(
                    value = ui.form.hostname,
                    onChange = vm::onHostname,
                    placeholder = "host.example.com",
                    monospace = true,
                )
            }
            if (type == "ping") {
                item { KumaSectionHeader("Packet size (optional)") }
                item {
                    KumaInputField(
                        value = ui.form.packetSize,
                        onChange = vm::onPacketSize,
                        placeholder = "56",
                        keyboardType = KeyboardType.Number,
                        monospace = true,
                    )
                }
            }
        }
        "dns" -> {
            item { KumaSectionHeader("Hostname") }
            item {
                KumaInputField(
                    value = ui.form.hostname,
                    onChange = vm::onHostname,
                    placeholder = "example.com",
                    monospace = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Resolver")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(
                                value = ui.form.dnsResolveServer,
                                onChange = vm::onDnsResolveServer,
                                placeholder = "1.1.1.1",
                                monospace = true,
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Record type")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(
                                value = ui.form.dnsResolveType,
                                onChange = vm::onDnsResolveType,
                                placeholder = "A",
                            )
                        }
                    }
                }
            }
        }
        "mqtt" -> {
            item { KumaSectionHeader("Broker host") }
            item {
                KumaInputField(
                    value = ui.form.hostname, onChange = vm::onHostname,
                    placeholder = "broker.example.com", monospace = true,
                )
            }
            item { KumaSectionHeader("Port") }
            item {
                KumaInputField(
                    value = ui.form.port, onChange = vm::onPort,
                    placeholder = "1883", keyboardType = KeyboardType.Number, monospace = true,
                )
            }
            item { KumaSectionHeader("Topic") }
            item {
                KumaInputField(
                    value = ui.form.mqttTopic, onChange = vm::onMqttTopic,
                    placeholder = "kuma/heartbeat", monospace = true,
                )
            }
            item { KumaSectionHeader("Success message (optional)") }
            item {
                KumaInputField(
                    value = ui.form.mqttSuccessMessage, onChange = vm::onMqttSuccessMessage,
                    placeholder = "ok", monospace = true,
                )
            }
            item { KumaSectionHeader("Username (optional)") }
            item { KumaInputField(value = ui.form.mqttUsername, onChange = vm::onMqttUsername) }
            item { KumaSectionHeader("Password (optional)") }
            item {
                KumaInputField(
                    value = ui.form.mqttPassword, onChange = vm::onMqttPassword,
                    placeholder = "••••••••", password = true,
                )
            }
        }
        "docker" -> {
            item { KumaSectionHeader("Container name or ID") }
            item {
                KumaInputField(
                    value = ui.form.dockerContainer, onChange = vm::onDockerContainer,
                    placeholder = "redis", monospace = true,
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KumaCream2,
                    border = BorderStroke(1.dp, KumaCardBorder),
                ) {
                    Text(
                        "Docker host is configured in the Kuma web UI — kept as-is on save.",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        "grpc-keyword" -> {
            item { KumaSectionHeader("gRPC URL") }
            item {
                KumaInputField(
                    value = ui.form.grpcUrl, onChange = vm::onGrpcUrl,
                    placeholder = "grpc.example.com:50051", monospace = true,
                    keyboardType = KeyboardType.Uri,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Service")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(
                                value = ui.form.grpcServiceName, onChange = vm::onGrpcServiceName,
                                placeholder = "MyService", monospace = true,
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Column {
                            KumaSectionHeader("Method")
                            Spacer(Modifier.height(4.dp))
                            KumaInputField(
                                value = ui.form.grpcMethod, onChange = vm::onGrpcMethod,
                                placeholder = "Check", monospace = true,
                            )
                        }
                    }
                }
            }
            item { KumaSectionHeader("Protobuf definition") }
            item {
                KumaInputField(
                    value = ui.form.grpcProtobuf, onChange = vm::onGrpcProtobuf,
                    placeholder = "syntax = \"proto3\";\nservice MyService { rpc Check (Req) returns (Resp); }",
                    monospace = true, singleLine = false,
                )
            }
            item { KumaSectionHeader("Request body (JSON)") }
            item {
                KumaInputField(
                    value = ui.form.grpcBody, onChange = vm::onGrpcBody,
                    placeholder = "{}", monospace = true, singleLine = false,
                )
            }
            item { KumaSectionHeader("Metadata (JSON, optional)") }
            item {
                KumaInputField(
                    value = ui.form.grpcMetadata, onChange = vm::onGrpcMetadata,
                    placeholder = "{ \"x-trace\": \"yes\" }",
                    monospace = true, singleLine = false,
                )
            }
            item { KumaSectionHeader("Keyword") }
            item {
                KumaInputField(
                    value = ui.form.keyword, onChange = vm::onKeyword,
                    placeholder = "ok", monospace = true,
                )
            }
            item {
                BoolSwitchRow(
                    label = "Enable TLS",
                    checked = ui.form.grpcEnableTls,
                    onChange = vm::onGrpcEnableTls,
                )
            }
        }
        "kafka-producer" -> {
            item { KumaSectionHeader("Brokers (comma-separated)") }
            item {
                KumaInputField(
                    value = ui.form.kafkaProducerBrokers, onChange = vm::onKafkaBrokers,
                    placeholder = "broker1:9092, broker2:9092", monospace = true,
                )
            }
            item { KumaSectionHeader("Topic") }
            item {
                KumaInputField(
                    value = ui.form.kafkaProducerTopic, onChange = vm::onKafkaTopic,
                    placeholder = "kuma-heartbeat", monospace = true,
                )
            }
            item { KumaSectionHeader("Message") }
            item {
                KumaInputField(
                    value = ui.form.kafkaProducerMessage, onChange = vm::onKafkaMessage,
                    placeholder = "ok", monospace = true, singleLine = false,
                )
            }
            item {
                BoolSwitchRow(
                    label = "Use SSL",
                    checked = ui.form.kafkaProducerSsl,
                    onChange = vm::onKafkaSsl,
                )
            }
            item {
                BoolSwitchRow(
                    label = "Allow auto topic creation",
                    checked = ui.form.kafkaProducerAllowAutoTopicCreation,
                    onChange = vm::onKafkaAllowAutoTopicCreation,
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KumaCream2,
                    border = BorderStroke(1.dp, KumaCardBorder),
                ) {
                    Text(
                        "SASL options round-trip from the Kuma web UI — leave to it for now.",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        "real-browser" -> {
            item { KumaSectionHeader("URL") }
            item {
                KumaInputField(
                    value = ui.form.url, onChange = vm::onUrl,
                    placeholder = "https://example.com", monospace = true,
                    keyboardType = KeyboardType.Uri,
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KumaCream2,
                    border = BorderStroke(1.dp, KumaCardBorder),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            "Browser host is configured in the Kuma web UI — kept as-is on save.",
                            color = KumaSlate2,
                            fontFamily = KumaMono,
                            fontSize = KumaTypography.caption,
                        )
                        if (ui.form.remoteBrowsersId.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "host_id=${ui.form.remoteBrowsersId}",
                                color = KumaSlate2,
                                fontFamily = KumaMono,
                                fontSize = KumaTypography.captionSmall,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            item {
                KumaCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Type ${type.uppercase()}".uppercase(),
                            color = KumaSlate2,
                            fontFamily = KumaMono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KumaTypography.captionSmall,
                            letterSpacing = 0.6.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Type-specific fields aren't editable here yet — edit in the Kuma web UI. Existing values are preserved on save.",
                            color = KumaSlate2,
                            fontFamily = KumaFont,
                            fontSize = KumaTypography.captionLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (!ui.monitor?.hostname.isNullOrBlank()) {
                            KvLine("hostname", ui.monitor!!.hostname!!)
                        }
                        if (!ui.monitor?.url.isNullOrBlank() && ui.monitor?.url != "https://") {
                            KvLine("url", ui.monitor!!.url!!)
                        }
                        if (ui.monitor?.port != null) {
                            KvLine("port", ui.monitor.port.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalChips(seconds: Int, onSelect: (Int) -> Unit) {
    val options = listOf(30 to "30s", 60 to "1m", 300 to "5m", 900 to "15m")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (s, label) ->
            val isSel = s == seconds
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = if (isSel) KumaTerra else KumaSurface,
                border = if (isSel) null else BorderStroke(1.dp, KumaCardBorder),
                onClick = { onSelect(s) },
            ) {
                Box(
                    Modifier.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (isSel) Color.White else KumaInk,
                        fontFamily = KumaMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledStatField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label.uppercase(),
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        KumaInputField(
            value = value,
            onChange = onChange,
            keyboardType = KeyboardType.Number,
            monospace = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KumaInputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    password: Boolean = false,
) {
    val effectiveKeyboard = if (password) KeyboardType.Password else keyboardType
    // A11Y4: when a placeholder is supplied, mirror it to the field's
    // accessibility label so TalkBack reads "<placeholder>, edit text" once
    // the user has typed and the visible placeholder disappears. Without
    // this, screen readers only get "edit text, <value>" with no field
    // identification once the field is non-empty.
    val accessibilityModifier = if (placeholder != null) {
        Modifier.semantics { contentDescription = placeholder }
    } else Modifier
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder?.let {
            { Text(it, color = KumaSlate2, fontFamily = if (monospace) KumaMono else KumaFont, fontSize = KumaTypography.body) }
        },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = effectiveKeyboard),
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth().then(accessibilityModifier),
        shape = RoundedCornerShape(10.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = KumaInk,
            fontFamily = if (monospace) KumaMono else KumaFont,
            fontSize = KumaTypography.body,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = KumaCardBorder,
            focusedBorderColor = KumaTerra,
            unfocusedContainerColor = KumaSurface,
            focusedContainerColor = KumaSurface,
            cursorColor = KumaTerra,
            focusedTextColor = KumaInk,
            unfocusedTextColor = KumaInk,
        ),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AuthMethodChips(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "" to "None",
        "basic" to "Basic",
        "ntlm" to "NTLM",
        "oauth2-cc" to "OAuth2",
        "mtls" to "mTLS",
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, label) ->
            val isSel = key == current
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSel) KumaTerra else KumaSurface,
                border = if (isSel) null else BorderStroke(1.dp, KumaCardBorder),
                onClick = { onSelect(key) },
            ) {
                Text(
                    label,
                    color = if (isSel) Color.White else KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = KumaTypography.body,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun KvLine(key: String, value: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            "${key.uppercase()}=",
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.caption,
            modifier = Modifier.width(90.dp),
        )
        Text(
            value,
            color = KumaInk,
            fontFamily = KumaMono,
            fontSize = KumaTypography.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BoolSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = KumaInk,
            fontFamily = KumaFont,
            fontSize = KumaTypography.body,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = KumaTerra,
                uncheckedThumbColor = KumaSlate2,
                uncheckedTrackColor = KumaCream2,
                uncheckedBorderColor = KumaCardBorder,
            ),
        )
    }
}
