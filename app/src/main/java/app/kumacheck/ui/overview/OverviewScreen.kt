package app.kumacheck.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.R
import app.kumacheck.data.model.MonitorState
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.ui.monitors.MonitorsViewModel
import app.kumacheck.ui.overview.components.PinPickerSheet
import app.kumacheck.ui.overview.components.Sparkline
import app.kumacheck.ui.theme.*
import app.kumacheck.util.parseBeatTime
import app.kumacheck.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    vm: OverviewViewModel,
    connection: KumaSocket.Connection,
    onMonitorTap: (Int) -> Unit,
    onIncidentTap: (Int) -> Unit,
    onShowMonitorsFiltered: (MonitorsViewModel.Filter) -> Unit,
    onOpenSettings: () -> Unit,
    onMaintenanceTap: (Int) -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    var showPinPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var heartbeatExpanded by rememberSaveable { mutableStateOf(false) }
    val nowMs = rememberTickingNow()
    val visibleHeartbeats = remember(
        heartbeatExpanded, ui.allHeartbeats, ui.pinnedHeartbeats,
    ) {
        when {
            heartbeatExpanded -> ui.allHeartbeats
            ui.pinnedHeartbeats.isNotEmpty() -> ui.pinnedHeartbeats
            else -> ui.allHeartbeats.take(DEFAULT_HEARTBEAT_PREVIEW)
        }
    }
    // Build the id→response map once per snapshot (not per-item) so each
    // lazy row's `responseById[id]` lookup is O(1).
    val responseById = remember(ui.responseTimes) {
        ui.responseTimes.associateBy { it.monitor.id }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().background(KumaCream),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { DashboardHeader(ui, connection, onOpenSettings) }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    HeroStatusCard(ui, connection, onShowMonitorsFiltered)
                }
            }
            if (ui.activeMaintenance.isNotEmpty()) {
                items(ui.activeMaintenance, key = { "maint-${it.id}" }) { m ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        MaintenanceBanner(m, onClick = { onMaintenanceTap(m.id) })
                    }
                }
            }
            if (ui.certWarnings.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CertExpiryBanner(ui.certWarnings, onClick = onMonitorTap)
                    }
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    KumaSectionHeader(
                        text = "monitors · ${ui.total}",
                        trailing = {
                            IconButton(
                                onClick = { showPinPicker = true },
                                // No explicit size — let IconButton default to
                                // its 48dp touch target (A11Y7). The 15dp icon
                                // inside still controls visual size; the
                                // surrounding hit-rect is what matters for
                                // tap accessibility.
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Edit pinned monitors",
                                    tint = KumaSlate2,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        },
                    )
                }
            }
            // Lazy rows: each MonitorRow is its own LazyColumn item so
            // off-screen rows aren't composed during scroll. Pre-fix, all
            // ~67 rows were materialised inside a single non-lazy Column
            // wrapped in one `item { ... }`, which forced Compose to
            // measure + lay out the entire 67-row block whenever the
            // parent recomposed. Now only the rows actually intersecting
            // the viewport are composed.
            //
            // The card chrome is synthesised per row from
            // `visibleHeartbeats.size` + index — top-rounded on first,
            // bottom-rounded on last, flat in the middle — and the
            // parent's `Arrangement.spacedBy(14.dp)` is overridden for
            // these items via a negative top-padding so the rows visually
            // butt up against each other inside one continuous "card."
            itemsIndexed(
                items = visibleHeartbeats,
                key = { _, st -> "monitor-row-${st.monitor.id}" },
            ) { idx, st ->
                MonitorRowCardItem(
                    state = st,
                    pingMs = responseById[st.monitor.id]?.avgPingMs
                        ?: st.lastHeartbeat?.ping?.takeIf { it.isFinite() && it >= 0 }?.toInt(),
                    spark = responseById[st.monitor.id]?.recentPings.orEmpty(),
                    recentStatuses = ui.recentStatuses[st.monitor.id].orEmpty(),
                    nowMs = nowMs,
                    isFirst = idx == 0,
                    isLast = idx == visibleHeartbeats.lastIndex,
                    onClick = { onMonitorTap(st.monitor.id) },
                )
            }
            val totalCount = ui.allHeartbeats.size
            if (visibleHeartbeats.size < totalCount || heartbeatExpanded) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { heartbeatExpanded = !heartbeatExpanded }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (heartbeatExpanded) "Show less"
                            else "Show all $totalCount",
                            color = KumaTerra,
                            fontFamily = KumaMono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KumaTypography.captionLarge,
                        )
                    }
                }
            }
            recentIncidentsSection(ui.recentIncidents, onIncidentTap, nowMs)
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showPinPicker) {
        PinPickerSheet(
            monitors = ui.allHeartbeats.map { it.monitor },
            pinnedIds = ui.pinnedIds,
            onTogglePin = { id, on -> vm.setPinned(id, on) },
            onDismiss = { showPinPicker = false },
        )
    }
}

@Composable
private fun DashboardHeader(
    ui: OverviewViewModel.UiState,
    connection: KumaSocket.Connection,
    onOpenSettings: () -> Unit,
) {
    // Read from the shared root ticker so the greeting flips at midnight even
    // if the user keeps the app open across the day boundary.
    val nowMs = app.kumacheck.ui.common.LocalTickingNow.current.longValue
    val hour = remember(nowMs) {
        java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            .get(java.util.Calendar.HOUR_OF_DAY)
    }
    val greeting = when {
        hour < 5 -> "GOOD NIGHT"
        hour < 12 -> "GOOD MORNING"
        hour < 18 -> "GOOD AFTERNOON"
        else -> "GOOD EVENING"
    }
    val title = when {
        connection != KumaSocket.Connection.AUTHENTICATED && ui.total == 0 -> "Connecting…"
        ui.down > 0 -> pluralStringResource(R.plurals.overview_incident_count, ui.down, ui.down)
        ui.maintenance > 0 -> "Heads up"
        ui.total == 0 -> "Waiting"
        else -> "All systems"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                greeting,
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.caption,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Bold,
                fontSize = KumaTypography.display,
                letterSpacing = (-0.6).sp,
            )
        }
        // Mascot avatar — tap to open Settings until the new bottom nav lands.
        // A11Y6 + A11Y7: bumped to 48dp (Material minimum touch target) and
        // tagged with semantics so screen-reader users get a usable button
        // with a meaningful description (the bare clickable Box was
        // invisible to TalkBack).
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .clickable(
                    onClick = onOpenSettings,
                    onClickLabel = "Open settings",
                    role = androidx.compose.ui.semantics.Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            KumaMark(size = 36.dp)
        }
    }
}

@Composable
private fun HeroStatusCard(
    ui: OverviewViewModel.UiState,
    connection: KumaSocket.Connection,
    onTapFilter: (MonitorsViewModel.Filter) -> Unit,
) {
    val isDown = ui.down > 0
    val isWarn = ui.maintenance > 0
    val incidentColor = when {
        isDown -> KumaDown
        isWarn -> KumaWarn
        else -> KumaUp
    }
    val baseLabel = when {
        ui.total == 0 && connection != KumaSocket.Connection.AUTHENTICATED -> "Connecting…"
        ui.total == 0 -> "No monitors"
        isDown -> pluralStringResource(R.plurals.overview_incident_count, ui.down, ui.down)
        isWarn -> "${ui.maintenance} maintenance"
        else -> "All systems operational"
    }
    val incidentLabel = if (ui.paused > 0 && ui.total > 0) {
        "$baseLabel · ${ui.paused} paused"
    } else baseLabel

    KumaCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    status = when {
                        isDown -> MonitorStatus.DOWN
                        isWarn -> MonitorStatus.MAINTENANCE
                        else -> MonitorStatus.UP
                    },
                    size = 8.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    incidentLabel,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.body,
                )
                if (ui.recentIncidents.isNotEmpty() && isDown) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "·  just now",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile("UP", ui.up, KumaUp, Modifier.weight(1f),
                    onClick = { onTapFilter(MonitorsViewModel.Filter.UP) })
                StatTile("WARN", ui.maintenance, KumaWarn, Modifier.weight(1f),
                    onClick = { onTapFilter(MonitorsViewModel.Filter.MAINTENANCE) })
                StatTile("DOWN", ui.down, KumaDown, Modifier.weight(1f),
                    onClick = { onTapFilter(MonitorsViewModel.Filter.DOWN) })
                StatTile("PAUSED", ui.paused, KumaSlate2, Modifier.weight(1f),
                    onClick = { onTapFilter(MonitorsViewModel.Filter.PAUSED) })
            }
            // 24h heat strip — true 24h binned aggregate (48 cells × 30 min)
            // computed from socket.recentBeats with timestamps. Bins with no
            // beats render as UNKNOWN (faded).
            val cells = ui.heatStrip24h
            if (cells.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                StatusGrid(cells, modifier = Modifier.fillMaxWidth(), height = incidentColor.let { 16.dp })
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("24h ago", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                    Text("now", color = KumaSlate2, fontFamily = KumaMono, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            // A2 (a11y): explicit Role.Button + onClickLabel so TalkBack
            // announces "Filter to <label>, button" rather than just
            // reading the value + label without indicating tappability.
            .clickable(
                onClick = onClick,
                onClickLabel = "Filter monitors to $label",
                role = androidx.compose.ui.semantics.Role.Button,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value.toString(),
            color = accent,
            fontFamily = KumaMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = KumaTypography.display,
            letterSpacing = (-0.5).sp,
        )
        Text(
            label,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.captionSmall,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MaintenanceBanner(m: KumaSocket.Maintenance, onClick: () -> Unit) {
    KumaAccentCard(accent = KumaSlate, onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Build, contentDescription = null, tint = KumaSlate)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "MAINTENANCE",
                    color = KumaSlate,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    m.title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.body,
                )
            }
        }
    }
}

/**
 * Banner that appears on Dashboard when one or more HTTPS monitors have a
 * cert that's already invalid or expiring within 30 days. Tap a row to jump
 * to that monitor's detail; non-tappable header summarizes count + soonest
 * value.
 */
@Composable
private fun CertExpiryBanner(
    warnings: List<OverviewViewModel.CertWarning>,
    onClick: (Int) -> Unit,
) {
    val hasInvalid = warnings.any { !it.valid }
    val accent = if (hasInvalid) KumaDown else KumaWarn
    val soonest = warnings.firstOrNull()
    val headline = when {
        hasInvalid -> "Certificate problem"
        warnings.size == 1 -> "Certificate expiring soon"
        else -> "${warnings.size} certificates expiring soon"
    }
    val subline = when {
        hasInvalid && soonest != null && !soonest.valid -> "${soonest.monitorName} — invalid"
        soonest?.daysRemaining != null ->
            "${soonest.monitorName} in ${soonest.daysRemaining}d"
        else -> null
    }
    // Single-warning case: hand `onClick` to KumaAccentCard so the entire
    // banner is tappable. Multi-warning case: per-row click handlers below
    // route to the individual monitor; the header is decorative.
    KumaAccentCard(
        accent = accent,
        onClick = warnings.singleOrNull()?.let { single -> { onClick(single.monitorId) } },
    ) {
        Column {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hasInvalid) "CERT INVALID" else "CERT EXPIRING",
                        color = accent,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.captionSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                    )
                    Text(
                        headline,
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.body,
                    )
                    if (subline != null) {
                        Text(
                            subline,
                            color = KumaSlate2,
                            fontFamily = KumaFont,
                            fontSize = KumaTypography.captionLarge,
                        )
                    }
                }
            }
            // Tappable rows when more than one monitor is affected — single
            // monitor case is handled by the card-level onClick above.
            if (warnings.size > 1) {
                HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(horizontal = 14.dp))
                // C9: key by monitorId so a reordered or removed warning
                // doesn't invalidate every row beneath it. Inline rows
                // (not LazyColumn) still benefit from `key()` for state
                // preservation across recompositions.
                warnings.take(4).forEach { w -> androidx.compose.runtime.key(w.monitorId) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick(w.monitorId) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            w.monitorName,
                            color = KumaInk,
                            fontFamily = KumaFont,
                            fontSize = KumaTypography.captionLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        val tag = when {
                            !w.valid -> "INVALID"
                            w.daysRemaining != null -> "${w.daysRemaining}d"
                            else -> "—"
                        }
                        val tagColor = if (!w.valid || (w.daysRemaining ?: 0) <= 14) KumaDown else KumaWarn
                        Text(
                            tag,
                            color = tagColor,
                            fontFamily = KumaMono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = KumaTypography.caption,
                        )
                    }
                } }
            }
        }
    }
}

@Composable
private fun MonitorListCard(
    rows: List<MonitorState>,
    responses: List<OverviewViewModel.ResponseRow>,
    recentStatuses: Map<Int, List<MonitorStatus>>,
    nowMs: Long,
    onTap: (Int) -> Unit,
) {
    val responseById = remember(responses) { responses.associateBy { it.monitor.id } }
    KumaCard {
        Column {
            // C9: key by monitor id so a reordered list doesn't drop
            // remembered state on every row underneath the change.
            rows.forEachIndexed { idx, st -> androidx.compose.runtime.key(st.monitor.id) {
                MonitorRow(
                    state = st,
                    pingMs = responseById[st.monitor.id]?.avgPingMs
                        ?: st.lastHeartbeat?.ping?.takeIf { it.isFinite() && it >= 0 }?.toInt(),
                    spark = responseById[st.monitor.id]?.recentPings.orEmpty(),
                    recentStatuses = recentStatuses[st.monitor.id].orEmpty(),
                    nowMs = nowMs,
                    onClick = { onTap(st.monitor.id) },
                )
                if (idx != rows.lastIndex) {
                    HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(start = 16.dp))
                }
            } }
        }
    }
}

/**
 * Per-row card item used by the LazyColumn on Overview. Each row is its own
 * self-contained KumaCard so the LazyColumn's `Arrangement.spacedBy` simply
 * works between them — every row gets the same visual gap, no
 * synthesised-corner trickery, no `offset(y = -14.dp)` (which only shifts
 * paint, not layout, so it produced a "first two touch / rest have gaps"
 * artifact). Trades the "single continuous card" look for consistent
 * spacing — same visual as the dedicated Monitors tab.
 */
@Composable
private fun MonitorRowCardItem(
    state: MonitorState,
    pingMs: Int?,
    spark: List<Double>,
    recentStatuses: List<MonitorStatus>,
    nowMs: Long,
    @Suppress("UNUSED_PARAMETER") isFirst: Boolean,
    @Suppress("UNUSED_PARAMETER") isLast: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        KumaCard(onClick = onClick) {
            MonitorRow(
                state = state,
                pingMs = pingMs,
                spark = spark,
                recentStatuses = recentStatuses,
                nowMs = nowMs,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun MonitorRow(
    state: MonitorState,
    pingMs: Int?,
    spark: List<Double>,
    recentStatuses: List<MonitorStatus>,
    nowMs: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state.status, size = 9.dp, pulse = state.status == MonitorStatus.UP)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.monitor.name,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.body,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            // Per-tick cost reduction: prefer the heartbeat's pre-parsed
            // `timeMs` (set once at ingest, P2). Falls back to a memoised
            // SimpleDateFormat parse keyed on the raw string for older
            // heartbeats whose ingest path didn't populate timeMs.
            val hb = state.lastHeartbeat
            val timeMs = hb?.timeMs
                ?: remember(hb?.time) { parseBeatTime(hb?.time) }
            val ago = timeMs?.let { relativeTime(it, nowMs) }
            val subtitle = listOfNotNull(
                state.monitor.type.uppercase(),
                state.monitor.hostname,
                ago,
            ).joinToString(" · ")
            Text(
                subtitle,
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                maxLines = 1,
            )
        }
        // Trailing chart slot:
        //   - Ping-style monitors with a usable latency series → sparkline + ms readout.
        //   - Everything else (Docker, DNS, group, push, etc.) → status strip of the
        //     last N beats so the row says something more useful than "—".
        if (spark.size >= 2) {
            Spacer(Modifier.width(8.dp))
            val sparkColor = when (state.status) {
                MonitorStatus.DOWN -> KumaDown
                MonitorStatus.MAINTENANCE -> KumaSlate
                MonitorStatus.PENDING -> KumaWarn
                MonitorStatus.UP -> KumaUp
                MonitorStatus.UNKNOWN -> KumaPaused
            }
            Sparkline(
                points = spark,
                color = sparkColor,
                modifier = Modifier.width(54.dp).height(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                pingMs?.let { "${it}ms" } ?: "—",
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.caption,
                modifier = Modifier.widthIn(min = 38.dp),
            )
        } else if (recentStatuses.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            // Cap to the last N beats so each cell has visible width in the
            // 54dp slot — without this the row collapses into 1px slivers.
            StatusGrid(
                statuses = recentStatuses.takeLast(12),
                modifier = Modifier.width(54.dp),
                height = 14.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                statusWord(state.status, state.monitor.type),
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.caption,
                modifier = Modifier.widthIn(min = 38.dp),
            )
        } else {
            Spacer(Modifier.width(10.dp))
            Text(
                "—",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.caption,
                modifier = Modifier.widthIn(min = 38.dp),
            )
        }
    }
}

/**
 * Per-monitor-type status word for the Overview row's trailing slot. Uses the
 * MonitorTypeProfile registry so a Docker row reads "Healthy" / "Unhealthy"
 * and a Push row reads "Receiving" / "Silent" rather than generic "Up/Down".
 *
 * Truncates to keep the slot width predictable — the full word (e.g.
 * "Maintenance") is shown on the detail screen hero instead.
 */
private fun statusWord(status: MonitorStatus, type: String?): String {
    val profile = app.kumacheck.data.model.MonitorTypes.forType(type)
    val raw = when (status) {
        MonitorStatus.UP -> profile.healthyVerb
        MonitorStatus.DOWN -> profile.unhealthyVerb
        MonitorStatus.PENDING -> "Pend"
        MonitorStatus.MAINTENANCE -> "Maint"
        MonitorStatus.UNKNOWN -> "—"
    }
    // Cap to keep the row tidy — long verbs like "Unreachable" would shift the
    // strip column. Detail screen shows the full word.
    return if (raw.length > 7) raw.take(7) else raw
}

private fun LazyListScope.recentIncidentsSection(
    incidents: List<OverviewViewModel.Incident>,
    onMonitorTap: (Int) -> Unit,
    nowMs: Long,
) {
    if (incidents.isEmpty()) return
    item {
        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
            KumaSectionHeader(text = "recent incidents")
        }
    }
    item {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            KumaCard {
                Column {
                    val truncated = incidents.take(8)
                    truncated.forEachIndexed { idx, inc ->
                        IncidentRow(inc, nowMs, onClick = { onMonitorTap(inc.monitorId) })
                        if (idx != truncated.lastIndex) {
                            HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentRow(inc: OverviewViewModel.Incident, nowMs: Long, onClick: () -> Unit) {
    val isUp = inc.status == MonitorStatus.UP
    val color = if (isUp) KumaUp else KumaDown
    val bg = if (isUp) KumaUpBg else KumaDownBg
    val ago = relativeTime(inc.timestamp, nowMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isUp) Icons.AutoMirrored.Filled.TrendingUp
                else Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                inc.monitorName,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.body,
                maxLines = 1,
            )
            Text(
                if (isUp) "Recovered" else (inc.msg?.takeIf { it.isNotBlank() } ?: "Down"),
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
                maxLines = 1,
            )
        }
        Text(
            ago,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.captionSmall,
        )
    }
}

/**
 * Wrapper around the shared root ticker. Kept as a function call so existing
 * callers (`val nowMs = rememberTickingNow()`) don't need to change shape.
 */
@Composable
private fun rememberTickingNow(): Long =
    app.kumacheck.ui.common.LocalTickingNow.current.longValue


private const val DEFAULT_HEARTBEAT_PREVIEW = 6
