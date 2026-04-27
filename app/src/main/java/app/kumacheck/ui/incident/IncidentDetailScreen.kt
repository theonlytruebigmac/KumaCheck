package app.kumacheck.ui.incident

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.ui.theme.*
import app.kumacheck.util.relativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentDetailScreen(
    vm: IncidentDetailViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now = app.kumacheck.ui.common.LocalTickingNow.current.longValue

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.monitor?.name ?: "Incident",
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = KumaFont,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                            tint = KumaInk)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KumaCream,
                    titleContentColor = KumaInk,
                    navigationIconContentColor = KumaInk,
                ),
            )
        },
        bottomBar = {
            if (ui.isOngoing) {
                // A11Y9: navigationBarsPadding so the Acknowledge button
                // doesn't sit under the gesture bar on gesture-nav devices.
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)) {
                    if (ui.acknowledged) {
                        // Already acknowledged — show a static label so the
                        // user knows the local state stuck. Tapping returns.
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = KumaCream2,
                            border = androidx.compose.foundation.BorderStroke(1.dp, KumaCardBorder),
                            onClick = onBack,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Acknowledged",
                                    color = KumaSlate2,
                                    fontFamily = KumaFont,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                // Await the prefs write before navigating back —
                                // otherwise the parent re-derives "ongoing
                                // incidents" from a stale ack set on resume and
                                // the badge briefly re-shows.
                                scope.launch {
                                    vm.acknowledge()
                                    onBack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KumaInk,
                                contentColor = KumaCream,
                            ),
                        ) {
                            Text(
                                "Acknowledge incident",
                                fontFamily = KumaFont,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                IncidentHeader(
                    isOngoing = ui.isOngoing,
                    name = ui.monitor?.name ?: "—",
                    startedMs = ui.incidentStartMs,
                    msg = ui.incidentMsg,
                    type = ui.monitor?.type ?: "—",
                    now = now,
                )
            }
            item {
                DownHero(
                    isOngoing = ui.isOngoing,
                    downDurationMs = ui.downDurationMs,
                    lastSeenMs = ui.lastSeenMs,
                    strip = ui.strip,
                    stripStartMs = ui.stripStartMs,
                    now = now,
                )
            }
            item { KumaSectionHeader(text = "Timeline") }
            if (ui.timeline.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No timeline events.", color = KumaSlate2, fontFamily = KumaFont)
                    }
                }
            } else {
                itemsIndexed(ui.timeline) { idx, event ->
                    TimelineRow(event, isLast = idx == ui.timeline.lastIndex)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IncidentHeader(
    isOngoing: Boolean,
    name: String,
    startedMs: Long?,
    msg: String?,
    type: String,
    now: Long,
) {
    val labelColor = if (isOngoing) KumaDown else KumaSlate
    val labelText = if (isOngoing) "INCIDENT · ONGOING" else "INCIDENT · RESOLVED"
    val started = startedMs?.let { "Started ${relativeTime(it, now)}" } ?: "—"
    val sub = listOfNotNull(started, msg ?: "$type ${if (isOngoing) "failure" else "outage"}").joinToString(" · ")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(labelColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                labelText,
                color = labelColor,
                fontFamily = KumaMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.7.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "$name stopped responding".takeIf { isOngoing } ?: "$name recovered",
            color = KumaInk,
            fontFamily = KumaFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.4).sp,
            lineHeight = 26.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            sub,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DownHero(
    isOngoing: Boolean,
    downDurationMs: Long,
    lastSeenMs: Long?,
    strip: List<MonitorStatus>,
    stripStartMs: Long?,
    now: Long,
) {
    val accent = if (isOngoing) KumaDown else KumaUp
    val accentBg = if (isOngoing) KumaDownBg else KumaUpBg
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KumaCardCorner),
        color = accentBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (isOngoing) "Down for ${formatDuration(downDurationMs)}"
                        else "Resolved · was down ${formatDuration(downDurationMs)}",
                        color = accent,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    if (lastSeenMs != null) {
                        Text(
                            "Last seen ${SimpleDateFormat("HH:mm:ss", locale).format(Date(lastSeenMs))}",
                            color = accent.copy(alpha = 0.75f),
                            fontFamily = KumaMono,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (strip.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                StatusGrid(strip, modifier = Modifier.fillMaxWidth(), height = 16.dp)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val leadingLabel = stripStartMs?.let {
                        val rel = relativeTime(it, now)
                        if (rel == "now") "moments ago" else "$rel ago"
                    } ?: "—"
                    Text(leadingLabel, color = accent.copy(alpha = 0.7f), fontFamily = KumaMono, fontSize = 9.sp)
                    Text("now", color = accent.copy(alpha = 0.7f), fontFamily = KumaMono, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(event: IncidentDetailViewModel.TimelineEvent, isLast: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier.width(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(event.status, size = 8.dp)
            if (!isLast) {
                Spacer(modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(KumaCardBorder))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            val timeStr = SimpleDateFormat("HH:mm", locale).format(Date(event.timestamp))
            val sub = listOfNotNull(timeStr, event.sub).joinToString(" · ")
            Text(
                sub,
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = 10.sp,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    if (totalSec < 60) return "${totalSec}s"
    val mins = totalSec / 60
    val secs = totalSec % 60
    if (mins < 60) return "${mins}m ${secs}s"
    val hrs = mins / 60
    val rmins = mins % 60
    return "${hrs}h ${rmins}m"
}
