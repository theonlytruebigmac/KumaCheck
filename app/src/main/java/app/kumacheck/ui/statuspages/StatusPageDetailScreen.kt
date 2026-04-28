package app.kumacheck.ui.statuspages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.model.StatusPageIncident
import app.kumacheck.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusPageDetailScreen(
    vm: StatusPageDetailViewModel,
    onBack: () -> Unit,
    onMonitorTap: (Int) -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val title = ui.detail?.page?.title ?: "Status page"

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title, maxLines = 1,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = KumaFont,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KumaCream,
                    titleContentColor = KumaInk,
                    navigationIconContentColor = KumaInk,
                ),
            )
        },
    ) { padding ->
        when {
            ui.isLoading && ui.detail == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Loading…", color = KumaSlate2, fontFamily = KumaFont) }
            ui.error != null && ui.detail == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // UX2: explicit Retry affordance so the user doesn't need
                // to back out and re-tap the page from the list.
                app.kumacheck.ui.common.ErrorRetryRow(
                    message = ui.error!!,
                    onRetry = vm::refresh,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Spacer(Modifier.height(2.dp)) }
                item { OverallBanner(ui) }
                ui.detail?.incident?.let { incident ->
                    item { KumaSectionHeader(text = "Active incident") }
                    item {
                        IncidentCard(
                            incident = incident,
                            onUnpin = vm::unpinIncident,
                        )
                    }
                }
                // Post-incident affordance — visible only when no active incident.
                if (ui.detail != null && ui.detail?.incident == null) {
                    item {
                        Box(modifier = Modifier.padding(top = 4.dp)) {
                            PostIncidentLauncher(onSubmit = vm::postIncident)
                        }
                    }
                }
                if (ui.groups.isNotEmpty()) {
                    ui.groups.forEach { group ->
                        item {
                            Box(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
                                KumaSectionHeader(text = group.name)
                            }
                        }
                        item { GroupCard(group.monitors, onMonitorTap) }
                    }
                } else if (ui.detail != null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No monitor groups configured.",
                                color = KumaSlate2,
                                fontFamily = KumaFont,
                                fontSize = KumaTypography.captionLarge,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun OverallBanner(ui: StatusPageDetailViewModel.UiState) {
    val allMonitors = ui.groups.flatMap { it.monitors }
    val downCount = allMonitors.count { it.state.status == MonitorStatus.DOWN }
    val maintCount = allMonitors.count { it.state.status == MonitorStatus.MAINTENANCE }
    val pausedCount = allMonitors.count {
        !it.state.monitor.active || it.state.monitor.forceInactive
    }
    val accent = when {
        downCount > 0 -> KumaDown
        maintCount > 0 -> KumaWarn
        else -> KumaUp
    }
    val accentBg = when {
        downCount > 0 -> KumaDownBg
        maintCount > 0 -> KumaWarnBg
        else -> KumaUpBg
    }
    val title = when {
        downCount > 0 -> "$downCount ${if (downCount == 1) "service" else "services"} down"
        maintCount > 0 -> "$maintCount under maintenance"
        else -> "All services operational"
    }
    // Ticks every 30s via the shared root ticker — without this the "Updated"
    // header would freeze at the time the screen first composed.
    val now = app.kumacheck.ui.common.LocalTickingNow.current.longValue
    val time = remember(now) {
        java.text.SimpleDateFormat("HH:mm zzz", java.util.Locale.getDefault())
            .format(java.util.Date(now))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KumaCardCorner),
        color = accentBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = accent,
                fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.title,
            )
            Spacer(Modifier.height(4.dp))
            val baseMeta = "Updated $time · ${allMonitors.size} ${if (allMonitors.size == 1) "monitor" else "monitors"}"
            val meta = if (pausedCount > 0) "$baseMeta · $pausedCount paused" else baseMeta
            Text(
                meta,
                color = accent.copy(alpha = 0.75f),
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun IncidentCard(
    incident: StatusPageIncident,
    onUnpin: () -> Unit,
) {
    val accent = when (incident.style) {
        app.kumacheck.data.model.IncidentStyle.DANGER -> KumaDown
        app.kumacheck.data.model.IncidentStyle.WARNING -> KumaWarn
        app.kumacheck.data.model.IncidentStyle.INFO -> KumaSlate
        app.kumacheck.data.model.IncidentStyle.PRIMARY -> KumaUp
    }
    var confirmUnpin by remember { mutableStateOf(false) }
    KumaAccentCard(accent = accent) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    incident.style.wire.uppercase(),
                    color = accent,
                    fontFamily = KumaMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.captionSmall,
                    letterSpacing = 0.6.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                incident.title,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.bodyEmphasis,
            )
            if (incident.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    incident.content,
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                )
            }
            if (!incident.createdDate.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Posted ${incident.createdDate}",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { confirmUnpin = true }) {
                Text(
                    "Unpin incident",
                    color = accent,
                    fontFamily = KumaMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.caption,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
    if (confirmUnpin) {
        AlertDialog(
            onDismissRequest = { confirmUnpin = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpin = false
                    onUnpin()
                }) { Text("Unpin", color = accent, fontFamily = KumaFont, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpin = false }) {
                    Text("Cancel", color = KumaSlate2, fontFamily = KumaFont)
                }
            },
            title = { Text("Unpin this incident?", fontFamily = KumaFont, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Visitors of this status page will no longer see the announcement.",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.body,
                )
            },
            containerColor = KumaCream,
        )
    }
}

@Composable
private fun PostIncidentLauncher(
    onSubmit: (
        title: String,
        content: String,
        style: app.kumacheck.data.model.IncidentStyle,
        onDone: () -> Unit,
    ) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KumaCardCorner),
        color = KumaCream2,
        border = androidx.compose.foundation.BorderStroke(1.dp, KumaCardBorder),
        onClick = { open = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50))
                    .background(KumaSlate2),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Post an incident announcement",
                color = KumaInk,
                fontFamily = KumaFont,
                fontSize = KumaTypography.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "+",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontWeight = FontWeight.Bold,
                fontSize = KumaTypography.title,
            )
        }
    }
    if (open) {
        PostIncidentDialog(
            onDismiss = { open = false },
            onSubmit = { title, content, style ->
                onSubmit(title, content, style) { open = false }
            },
        )
    }
}

@Composable
private fun PostIncidentDialog(
    onDismiss: () -> Unit,
    onSubmit: (
        title: String,
        content: String,
        style: app.kumacheck.data.model.IncidentStyle,
    ) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(app.kumacheck.data.model.IncidentStyle.WARNING) }
    val canSubmit = title.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (canSubmit) onSubmit(title.trim(), content.trim(), style) },
                enabled = canSubmit,
            ) {
                Text(
                    "Post",
                    color = if (canSubmit) KumaTerra else KumaSlate2,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KumaSlate2, fontFamily = KumaFont)
            }
        },
        title = { Text("Post incident", fontFamily = KumaFont, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", fontFamily = KumaFont) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Description (optional)", fontFamily = KumaFont) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                Text(
                    "Style",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                    letterSpacing = 0.6.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.kumacheck.data.model.IncidentStyle.entries.forEach { s ->
                        val isSel = s == style
                        val accent = when (s) {
                            app.kumacheck.data.model.IncidentStyle.DANGER -> KumaDown
                            app.kumacheck.data.model.IncidentStyle.WARNING -> KumaWarn
                            app.kumacheck.data.model.IncidentStyle.INFO -> KumaSlate
                            app.kumacheck.data.model.IncidentStyle.PRIMARY -> KumaUp
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) accent else KumaCream2,
                            border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, KumaCardBorder),
                            onClick = { style = s },
                        ) {
                            Text(
                                s.wire,
                                color = if (isSel) Color.White else KumaInk,
                                fontFamily = KumaMono,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = KumaTypography.caption,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        containerColor = KumaCream,
    )
}

@Composable
private fun GroupCard(monitors: List<StatusPageDetailViewModel.MonitorRow>, onMonitorTap: (Int) -> Unit) {
    if (monitors.isEmpty()) {
        KumaCard {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No live data for monitors in this group.",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                )
            }
        }
        return
    }
    KumaCard {
        Column {
            monitors.forEachIndexed { idx, row ->
                if (idx > 0) {
                    HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(start = 14.dp))
                }
                GroupRow(row, onClick = { onMonitorTap(row.state.monitor.id) })
            }
        }
    }
}

@Composable
private fun GroupRow(row: StatusPageDetailViewModel.MonitorRow, onClick: () -> Unit) {
    val st = row.state
    val statusLabel = when (st.status) {
        MonitorStatus.UP -> "Operational"
        MonitorStatus.DOWN -> "Down"
        MonitorStatus.PENDING -> "Pending"
        MonitorStatus.MAINTENANCE -> "Maintenance"
        MonitorStatus.UNKNOWN -> "Unknown"
    }
    val statusColor = statusColor(st.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                st.monitor.name,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.body,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                statusLabel.uppercase(),
                color = statusColor,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.captionSmall,
                letterSpacing = 0.5.sp,
            )
        }
        // 30-cell heat strip — real recent-beat history (oldest → newest), padded
        // with UNKNOWN on the left when fewer beats are buffered.
        StatusGrid(
            statuses = padStrip(row.history, cells = StatusPageDetailViewModel.STRIP_CELLS),
            modifier = Modifier.fillMaxWidth(),
            height = 14.dp,
        )
    }
}

private fun padStrip(history: List<MonitorStatus>, cells: Int): List<MonitorStatus> {
    if (history.isEmpty()) return List(cells) { MonitorStatus.UNKNOWN }
    val tail = history.takeLast(cells)
    val padding = cells - tail.size
    return if (padding > 0) List(padding) { MonitorStatus.UNKNOWN } + tail else tail
}
