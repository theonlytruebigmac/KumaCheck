package app.kumacheck.ui.monitors

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.ui.theme.*
import app.kumacheck.util.relativeTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorsScreen(
    vm: MonitorsViewModel,
    onMonitorTap: (Int) -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val expanded by vm.expandedFolders.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val nowMs = app.kumacheck.ui.common.LocalTickingNow.current.longValue

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().background(KumaCream),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        "${ui.totalMonitorCount} SERVICES · LIVE",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        letterSpacing = 0.6.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Monitors",
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = KumaTypography.display,
                        letterSpacing = (-0.6).sp,
                    )
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SearchBar(query, vm::setQuery)
                }
            }
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChipRow(filter, ui.countsByFilter, onSelect = vm::setFilter)
                }
            }
            // Flat folder + row items so a folder with hundreds of monitors
            // renders lazily — the prior `forEachIndexed` inside FolderSection
            // materialized the entire row list on expand. Each folder now
            // emits a header item plus, when expanded, one item per row with
            // a stable composite key.
            ui.folders.forEach { folder ->
                val isExpanded = computeExpanded(folder, expanded, vm)
                item(key = "folderHeader_${folder.id}") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        FolderHeaderCard(
                            folder = folder,
                            expanded = isExpanded,
                            onToggle = { vm.toggleFolder(folder.id) },
                        )
                    }
                }
                if (isExpanded) {
                    items(
                        items = folder.rows,
                        key = { row -> "folderRow_${folder.id}_${row.monitor.id}" },
                    ) { row ->
                        // C10: memoise the per-row click lambda so its identity
                        // is stable across recompositions — a fresh lambda per
                        // recompose defeats Compose's parameter-stability check
                        // and forces MonitorRow to re-skip nothing.
                        val onClick = remember(row.monitor.id, onMonitorTap) {
                            { onMonitorTap(row.monitor.id) }
                        }
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            KumaCard {
                                MonitorRow(row, nowMs, onClick = onClick)
                            }
                        }
                    }
                }
            }
            if (ui.folders.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No monitors match",
                            color = KumaSlate2,
                            fontFamily = KumaFont,
                            fontSize = KumaTypography.body,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search monitors", color = KumaSlate2) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = KumaSlate2) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = KumaSlate2)
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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

private fun computeExpanded(
    folder: MonitorsViewModel.Folder,
    expandedSet: Set<Int>,
    vm: MonitorsViewModel,
): Boolean {
    val defaultOpen = vm.isExpandedByDefault(folder)
    val overridden = folder.id in expandedSet
    return if (defaultOpen) !overridden else overridden
}

@Composable
private fun FilterChipRow(
    selected: MonitorsViewModel.Filter,
    counts: Map<MonitorsViewModel.Filter, Int>,
    onSelect: (MonitorsViewModel.Filter) -> Unit,
) {
    val items = listOf(
        Triple(MonitorsViewModel.Filter.ALL, "All", null as Color?),
        Triple(MonitorsViewModel.Filter.UP, "Up", KumaUp),
        Triple(MonitorsViewModel.Filter.DOWN, "Down", KumaDown),
        Triple(MonitorsViewModel.Filter.MAINTENANCE, "Maint", KumaSlate),
        Triple(MonitorsViewModel.Filter.PAUSED, "Paused", KumaPaused),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (f, label, accent) ->
            val isSel = f == selected
            val n = counts[f] ?: 0
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSel) KumaInk else KumaSurface,
                border = if (isSel) null else BorderStroke(1.dp, KumaCardBorder),
                onClick = { onSelect(f) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        label,
                        color = if (isSel) KumaCream else KumaInk,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        n.toString(),
                        color = if (isSel) KumaCream.copy(alpha = 0.7f) else (accent ?: KumaSlate2),
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Just the header card for a folder — chevron + name + counts. The folder's
 * monitor rows are emitted as separate sibling items in the outer LazyColumn
 * so a 500-monitor folder doesn't materialize all rows on expand.
 */
@Composable
private fun FolderHeaderCard(
    folder: MonitorsViewModel.Folder,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrowRot by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    KumaCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KumaSlate2,
                    modifier = Modifier.rotate(arrowRot),
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(
                    if (folder.downCount > 0) MonitorStatus.DOWN else MonitorStatus.UP,
                    size = 8.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    folder.name,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (folder.downCount > 0) {
                    StatusBadge(MonitorStatus.DOWN, label = "${folder.downCount} Down")
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    folder.rows.size.toString(),
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                )
        }
    }
}

@Composable
private fun MonitorRow(row: MonitorsViewModel.Row, nowMs: Long, onClick: () -> Unit) {
    val subtitle = listOfNotNull(
        row.monitor.type.uppercase(),
        row.monitor.hostname?.takeIf { it.isNotBlank() },
        row.monitor.url?.takeIf { it.isNotBlank() && it != "https://" },
        row.lastBeatMs?.let { relativeTime(it, nowMs) },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(row.status, size = 9.dp, pulse = row.status == MonitorStatus.UP)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.monitor.name,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.body,
                maxLines = 1,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.captionSmall,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                row.lastPingMs?.takeIf { it.isFinite() && it >= 0 }?.let { "${it.toInt()}ms" } ?: "—",
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.captionLarge,
            )
            Text(
                row.uptime24h?.let { "${(it * 100).format1()}%" } ?: "—",
                color = if ((row.uptime24h ?: 1.0) >= 0.99) KumaUp else KumaWarn,
                fontFamily = KumaMono,
                fontSize = KumaTypography.captionSmall,
            )
        }
    }
}

private fun Double.format1(): String {
    val rounded = kotlin.math.round(this * 10) / 10.0
    // A4: pin to Locale.US so the dot-decimal stays consistent with the rest
    // of the app. `Double.toString()` is locale-independent today, but
    // `String.format` defaults to `Locale.getDefault()` and would render
    // "99,5" for German locales — keep the digit grouping deterministic.
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
}
