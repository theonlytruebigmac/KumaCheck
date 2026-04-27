package app.kumacheck.ui.incident

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.IncidentLogEntry
import app.kumacheck.data.model.MonitorStatus
import app.kumacheck.ui.theme.*
import app.kumacheck.util.relativeTime

@Composable
fun IncidentsListScreen(
    vm: IncidentsListViewModel,
    onIncidentTap: (Int) -> Unit,
) {
    val incidents by vm.incidents.collectAsState()
    val nowMs = app.kumacheck.ui.common.LocalTickingNow.current.longValue

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(KumaCream),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "${incidents.size} EVENTS",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Incidents",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.6).sp,
                )
            }
        }
        if (incidents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No incidents recorded yet.",
                        color = KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    KumaCard {
                        Column {
                            incidents.forEachIndexed { idx, inc ->
                                IncidentListRow(inc, nowMs, onClick = { onIncidentTap(inc.monitorId) })
                                if (idx != incidents.lastIndex) {
                                    HorizontalDivider(
                                        color = KumaCardBorder,
                                        modifier = Modifier.padding(start = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IncidentListRow(
    inc: IncidentLogEntry,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val isUp = inc.status == MonitorStatus.UP
    val color = if (isUp) KumaUp else KumaDown
    val bg = if (isUp) KumaUpBg else KumaDownBg
    val ago = relativeTime(inc.timestampMs, nowMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
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
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                if (isUp) "Recovered" else inc.msg.takeIf { it.isNotBlank() } ?: "Down",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Text(
            ago,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = 10.sp,
        )
    }
}
