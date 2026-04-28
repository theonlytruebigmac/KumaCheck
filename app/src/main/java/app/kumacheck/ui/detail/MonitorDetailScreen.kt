package app.kumacheck.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.DisplayMode
import app.kumacheck.data.model.Heartbeat
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.model.MonitorTypes
import app.kumacheck.util.parseBeatTime
import app.kumacheck.ui.theme.*
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorDetailScreen(
    vm: MonitorDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }
    var showDeleteConfirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            DetailTopBar(
                ui = ui,
                onBack = onBack,
                onEdit = onEdit,
                onDelete = { showDeleteConfirm = true },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    HeroStatCard(ui)
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    StatsGrid(ui)
                }
            }
            if (ui.certInfo != null) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CertCard(ui.certInfo!!)
                    }
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    MuteCard(ui.muted, vm::setMuted)
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    KumaSectionHeader("recent checks")
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    RecentChecksList(ui.history)
                }
            }
            if (ui.error != null) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        KumaAccentCard(accent = KumaDown, onClick = vm::dismissError) {
                            Text(
                                ui.error!!,
                                color = KumaDown,
                                fontFamily = KumaFont,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete monitor?", color = KumaInk, fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This permanently removes the monitor and its history from the server.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete()
                }) {
                    Text("Delete", color = KumaDown, fontWeight = FontWeight.SemiBold,
                        fontFamily = KumaFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = KumaSlate, fontFamily = KumaFont)
                }
            },
            containerColor = KumaSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    ui: MonitorDetailViewModel.UiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = ui.latest?.status ?: MonitorStatus.UNKNOWN
    val intervalText = "60s"   // not exposed on the VM today; placeholder.
    TopAppBar(
        title = {
            Column {
                Text(
                    "${(ui.monitor?.type ?: "—").uppercase()} · $intervalText",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    ui.monitor?.name ?: "Monitor",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = KumaInk)
            }
        },
        actions = {
            StatusDot(status, size = 10.dp, pulse = status == MonitorStatus.UP)
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit monitor", tint = KumaSlate)
            }
            IconButton(onClick = onDelete, enabled = !ui.deleting) {
                if (ui.deleting) {
                    // Visible feedback while the deleteMonitor RPC is in
                    // flight — the suspend roundtrips to Kuma + the SQL
                    // delete + the monitorList push, which can take a
                    // couple of seconds on a busy server. Without this
                    // the user sees the dialog dismiss and nothing else
                    // happens until the screen pops, and assumes the
                    // delete failed.
                    CircularProgressIndicator(
                        color = KumaDown,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete monitor", tint = KumaDown)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = KumaCream,
            titleContentColor = KumaInk,
            navigationIconContentColor = KumaInk,
        ),
    )
}

@Composable
private fun HeroStatCard(ui: MonitorDetailViewModel.UiState) {
    val profile = MonitorTypes.forType(ui.monitor?.type)
    val ping = ui.latest?.ping?.takeIf { it >= 0 }?.toInt()
    val pings = ui.history.mapNotNull { it.ping?.takeIf { p -> p >= 0 } }
    // Profile drives the layout. Stay on ResponseTimeHero for every
    // LATENCY-typed monitor, even when pings is empty — it gracefully
    // shows "—ms" and skips the chart until data lands. The previous
    // `if (pings.isNotEmpty())` fall-through to StatusHero caused a
    // visible layout swap on every detail-screen entry: frame 1 showed
    // the green "Reachable" status word, frame 2 (after the 24h history
    // fetch returned ~200ms later) replaced it with the ms chart.
    when (profile.mode) {
        DisplayMode.LATENCY -> ResponseTimeHero(
            ping = ping,
            pings = pings,
            isLoadingHistory = ui.isLoadingHistory,
            latestMsg = ui.latest?.msg,
        )
        DisplayMode.STATE,
        DisplayMode.AGGREGATE -> StatusHero(ui = ui, profile = profile)
    }
}

@Composable
private fun ResponseTimeHero(
    ping: Int?,
    pings: List<Double>,
    isLoadingHistory: Boolean,
    latestMsg: String?,
) {
    val deltaPct: Int? = if (pings.size >= 4 && ping != null) {
        val recentAvg = pings.takeLast(10).average()
        if (recentAvg > 0) ((ping - recentAvg) / recentAvg * 100).toInt() else null
    } else null
    KumaCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "RESPONSE TIME · NOW",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    ping?.toString() ?: "—",
                    color = KumaInk,
                    fontFamily = KumaMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    letterSpacing = (-1).sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "ms",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.bodyEmphasis,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (deltaPct != null) {
                    val arrow = if (deltaPct < 0) "↓" else "↑"
                    val pctColor = if (deltaPct < 0) KumaUp else KumaWarn
                    Text(
                        "$arrow ${kotlin.math.abs(deltaPct)}%",
                        color = pctColor,
                        fontFamily = KumaMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.captionLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (!latestMsg.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    latestMsg,
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                    maxLines = 1,
                )
            }
            if (pings.size >= 2) {
                Spacer(Modifier.height(14.dp))
                ResponseChart(pings, isLoading = isLoadingHistory)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("30 min", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                    Text("15", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                    Text("now", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusHero(
    ui: MonitorDetailViewModel.UiState,
    profile: app.kumacheck.data.model.MonitorTypeProfile,
) {
    val status = ui.latest?.status ?: MonitorStatus.UNKNOWN
    // Type-aware verbiage from the profile: Docker reads "Healthy/Unhealthy",
    // Push reads "Receiving/Silent", etc. Falls through to a generic
    // pending/maintenance/unknown for non-up/down states.
    val statusLabel = when (status) {
        MonitorStatus.UP -> profile.healthyVerb
        MonitorStatus.DOWN -> profile.unhealthyVerb
        MonitorStatus.PENDING -> "Pending"
        MonitorStatus.MAINTENANCE -> "Maintenance"
        MonitorStatus.UNKNOWN -> "—"
    }
    val statusColor = when (status) {
        MonitorStatus.UP -> KumaUp
        MonitorStatus.DOWN -> KumaDown
        MonitorStatus.PENDING -> KumaWarn
        MonitorStatus.MAINTENANCE -> KumaSlate
        MonitorStatus.UNKNOWN -> KumaPaused
    }
    val recentStatuses = ui.history.takeLast(30).map { it.status }
    val latestMsg = ui.latest?.msg?.takeIf { it.isNotBlank() }
    KumaCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "${profile.displayName.uppercase()} · NOW",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                statusLabel,
                color = statusColor,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 38.sp,
                letterSpacing = (-1).sp,
            )
            if (latestMsg != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    latestMsg,
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                    maxLines = 2,
                )
            }
            if (recentStatuses.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                StatusGrid(
                    statuses = recentStatuses,
                    modifier = Modifier.fillMaxWidth(),
                    height = 18.dp,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${recentStatuses.size} checks",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = 9.sp,
                    )
                    Text("now", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                }
            } else if (ui.isLoadingHistory) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(70.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(KumaCream2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", color = KumaSlate2, fontFamily = KumaMono, fontSize = KumaTypography.caption)
                }
            }
        }
    }
}

@Composable
private fun ResponseChart(pings: List<Double>, isLoading: Boolean) {
    // Terracotta bar chart matching the asset-pack mockup. Each bar's height
    // is proportional to its ping ms, scaled to the largest value. A subtle
    // 0.85 alpha keeps the bars readable against the white card.
    val maxPing = pings.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val barColor = KumaTerra.copy(alpha = 0.85f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
    ) {
        if (pings.isEmpty()) return@Canvas
        val gap = 2f.dp.toPx()
        val totalGap = gap * (pings.size - 1).coerceAtLeast(0)
        val barWidth = ((size.width - totalGap) / pings.size).coerceAtLeast(1f)
        pings.forEachIndexed { i, v ->
            val frac = (v / maxPing).toFloat().coerceIn(0f, 1f)
            val barH = size.height * frac
            val x = i * (barWidth + gap)
            val y = size.height - barH
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f.dp.toPx()),
            )
        }
    }
}

@Composable
private fun StatsGrid(ui: MonitorDetailViewModel.UiState) {
    val u24 = ui.uptime24h?.let { "${(it * 100).pct1()}%" } ?: "—"
    val u30 = ui.uptime30d?.let { "${(it * 100).pct1()}%" } ?: "—"
    val avgPing = ui.history.mapNotNull { it.ping?.takeIf { p -> p >= 0 } }
        .let { if (it.isEmpty()) "—" else "${(it.average().toInt())}ms" }
    // P5: re-derive against `now` so stale server snapshots don't show "10d"
    // when wall-clock has crossed into 3d territory.
    val certDays = ui.certInfo?.daysRemainingNow()?.let { "${it}d" } ?: "—"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("Uptime 24h", u24, Modifier.weight(1f))
            StatCell("Avg latency", avgPing, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("Uptime 30d", u30, Modifier.weight(1f))
            StatCell("Cert expires", certDays, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    KumaCard(modifier = modifier, corner = 10.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label.uppercase(),
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.title,
            )
        }
    }
}

@Composable
private fun CertCard(info: app.kumacheck.data.socket.KumaSocket.CertInfo) {
    // P5: client-derived against `now` (see CertInfo.daysRemainingNow).
    val days = info.daysRemainingNow()
    val accent = when {
        !info.valid -> KumaDown
        days == null -> KumaSlate
        days <= 14 -> KumaDown
        days <= 30 -> KumaWarn
        else -> KumaUp
    }
    KumaAccentCard(accent = accent) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("TLS", color = accent, fontFamily = KumaMono,
                    fontWeight = FontWeight.Bold, fontSize = KumaTypography.captionSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Certificate",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.body,
                )
                val sub = when {
                    !info.valid -> "Invalid certificate"
                    days == null -> "No expiry data"
                    days <= 0 -> "Expired"
                    else -> "Expires in ${days}d" + (info.validTo?.let { " · $it" } ?: "")
                }
                Text(
                    sub,
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                )
            }
            Text(
                if (days != null && days > 0) "${days}d"
                else if (info.valid) "—" else "INVALID",
                color = accent,
                fontFamily = KumaMono,
                fontWeight = FontWeight.Bold,
                fontSize = KumaTypography.body,
            )
        }
    }
}

@Composable
private fun MuteCard(muted: Boolean, onToggle: (Boolean) -> Unit) {
    KumaCard {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (muted) KumaWarnBg else KumaPausedBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.NotificationsOff, contentDescription = null,
                    tint = if (muted) KumaWarn else KumaSlate2,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Mute notifications",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = KumaTypography.body,
                )
                Text(
                    if (muted) "Alerts suppressed for this monitor"
                    else "Alert me on incidents and recoveries",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.caption,
                )
            }
            Switch(
                checked = muted,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = KumaTerra,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = KumaPaused,
                ),
            )
        }
    }
}

@Composable
private fun RecentChecksList(history: List<Heartbeat>) {
    if (history.isEmpty()) {
        KumaCard {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("No checks yet", color = KumaSlate2, fontFamily = KumaMono, fontSize = KumaTypography.caption)
            }
        }
        return
    }
    KumaCard {
        Column {
            history.takeLast(8).reversed().forEachIndexed { idx, hb ->
                if (idx > 0) HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(start = 14.dp))
                CheckRow(hb)
            }
        }
    }
}

private val HMS_FORMATTER = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

@Composable
private fun CheckRow(hb: Heartbeat) {
    // Parse-then-format so ISO `…T12:34:56Z` formats as `12:34:56` instead of
    // the prior `takeLast(8).take(8)` which emitted `34:56Z` garbage. Falls
    // back to the raw string if it doesn't parse — better to show something
    // odd than to crash.
    val time = (hb.timeMs ?: parseBeatTime(hb.time))
        ?.let { HMS_FORMATTER.format(java.util.Date(it)) }
        ?: hb.time
    // For status-only monitors (Docker etc.) ping is null/negative — drop the
    // trailing "—" entirely rather than echoing nothing under a Response Time
    // column that doesn't apply.
    val ms = hb.ping?.takeIf { it.isFinite() && it >= 0 }?.let { "${it.toInt()}ms" }
    val statusText = when (hb.status) {
        MonitorStatus.UP -> hb.msg.ifBlank { "200 OK" }
        MonitorStatus.DOWN -> hb.msg.ifBlank { "Failed" }
        MonitorStatus.PENDING -> "Pending"
        MonitorStatus.MAINTENANCE -> "Maintenance"
        MonitorStatus.UNKNOWN -> "Unknown"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.caption,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Spacer(Modifier.width(10.dp))
        StatusDot(hb.status, size = 7.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            statusText,
            color = KumaInk,
            fontFamily = KumaFont,
            fontSize = KumaTypography.captionLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (ms != null) {
            Text(
                ms,
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.captionLarge,
            )
        }
    }
}

private fun Double.pct1(): String {
    val rounded = kotlin.math.round(this * 100) / 100.0
    // Pin Locale.US — without it, German/French locales render "99,50" via the
    // default-locale `String.format`, which looks wrong in an English UI label.
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else String.format(java.util.Locale.US, "%.2f", rounded)
}
