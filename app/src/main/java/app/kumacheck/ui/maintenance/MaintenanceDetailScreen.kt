package app.kumacheck.ui.maintenance

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.ui.theme.KumaCard
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaCream2
import app.kumacheck.ui.theme.KumaDown
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaPaused
import app.kumacheck.ui.theme.KumaSectionHeader
import app.kumacheck.ui.theme.KumaSlate
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaSurface
import app.kumacheck.ui.theme.KumaTerra
import app.kumacheck.ui.theme.KumaUp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceDetailScreen(
    vm: MaintenanceDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMonitorTap: (Int) -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val m = ui.maintenance

    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }

    var showDeleteConfirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        m?.title ?: "Maintenance",
                        maxLines = 1,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (m != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit maintenance")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }, enabled = !ui.deleting) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete maintenance",
                                tint = KumaDown,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KumaCream,
                    titleContentColor = KumaInk,
                    navigationIconContentColor = KumaInk,
                    actionIconContentColor = KumaInk,
                ),
            )
        },
    ) { padding ->
        if (m == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Not found", color = KumaSlate2, fontFamily = KumaFont)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { HeroCard(m) }
            item { ActiveToggleCard(m, ui.toggling, vm::toggleActive) }
            if (ui.error != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = KumaDown.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KumaDown.copy(alpha = 0.4f)),
                        onClick = vm::dismissError,
                    ) {
                        Text(
                            ui.error!!, color = KumaDown,
                            fontFamily = KumaFont,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            if (!m.description.isNullOrBlank()) {
                item { KumaSectionHeader("Description") }
                item {
                    KumaCard {
                        Text(
                            m.description,
                            color = KumaInk,
                            fontFamily = KumaFont,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item { KumaSectionHeader("Schedule") }
            item { ScheduleCard(m) }
            item { KumaSectionHeader("Affected monitors") }
            item {
                AffectedMonitorsCard(
                    loading = ui.loadingAffected,
                    monitors = ui.affectedMonitors,
                    onTap = onMonitorTap,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    "Delete maintenance?",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    "This will permanently remove the maintenance window from the server.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.delete()
                }) {
                    Text(
                        "Delete",
                        color = KumaDown,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = KumaFont,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = KumaSlate2, fontFamily = KumaFont)
                }
            },
            containerColor = KumaSurface,
        )
    }
}

@Composable
private fun HeroCard(m: KumaSocket.Maintenance) {
    val accent = if (m.active) KumaTerra else KumaPaused
    KumaCard {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = accent,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (m.status ?: if (m.active) "Active" else "Inactive")
                        .replaceFirstChar { it.titlecase() },
                    color = accent,
                    fontFamily = KumaMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.caption,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    m.title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun ActiveToggleCard(
    m: KumaSocket.Maintenance,
    toggling: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    KumaCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Active",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Text(
                    if (m.active) "Currently in effect"
                    else "Paused — monitors report normally",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                )
            }
            Switch(
                checked = m.active,
                enabled = !toggling,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = KumaUp,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = KumaPaused,
                ),
            )
        }
    }
}

@Composable
private fun AffectedMonitorsCard(
    loading: Boolean,
    monitors: List<MaintenanceDetailViewModel.AffectedMonitor>,
    onTap: (Int) -> Unit,
) {
    KumaCard {
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Loading…",
                        color = KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
            }
            monitors.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No monitors attached",
                        color = KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
            }
            else -> {
                Column {
                    monitors.forEachIndexed { idx, am ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTap(am.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                am.name,
                                color = KumaInk,
                                fontFamily = KumaFont,
                                fontWeight = FontWeight.Medium,
                                fontSize = KumaTypography.body,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = KumaSlate2,
                            )
                        }
                        if (idx != monitors.lastIndex) {
                            HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(m: KumaSocket.Maintenance) {
    KumaCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            ScheduleRow("Strategy", m.strategy ?: "—")
            ScheduleDivider()
            ScheduleRow("Start", m.startDate ?: "—")
            ScheduleDivider()
            ScheduleRow("End", m.endDate ?: "—")
            if (m.durationMinutes != null) {
                ScheduleDivider()
                ScheduleRow("Duration", "${m.durationMinutes} min")
            }
            if (m.weekdays.isNotEmpty()) {
                ScheduleDivider()
                ScheduleRow("Weekdays", weekdayString(m.weekdays))
            }
            if (!m.timezone.isNullOrBlank()) {
                ScheduleDivider()
                ScheduleRow("Timezone", m.timezone)
            }
        }
    }
}

@Composable
private fun ScheduleRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = KumaInk,
            fontFamily = KumaFont,
            fontSize = KumaTypography.body,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.captionLarge,
        )
    }
}

@Composable
private fun ScheduleDivider() {
    HorizontalDivider(color = KumaCardBorder, modifier = Modifier.padding(horizontal = 16.dp))
}

private fun weekdayString(days: List<Int>): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return days.sorted().mapNotNull { names.getOrNull(it) }.joinToString(", ")
}
