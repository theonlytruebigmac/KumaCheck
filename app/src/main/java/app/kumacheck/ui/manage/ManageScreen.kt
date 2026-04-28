package app.kumacheck.ui.manage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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
import app.kumacheck.ui.theme.*

@Composable
fun ManageScreen(
    vm: ManageViewModel,
    onMonitorTap: (Int) -> Unit,
    onCreateMonitor: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(KumaCream),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "CONFIGURATION",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        letterSpacing = 0.6.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Manage",
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = KumaTypography.display,
                        letterSpacing = (-0.6).sp,
                    )
                }
                // A11Y8: 48dp Material minimum touch target.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = KumaTerra,
                    onClick = onCreateMonitor,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = "New monitor",
                            tint = Color.White)
                    }
                }
            }
        }
        items(ui.rows, key = { it.monitor.id }) { row ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ManageRow(
                    row = row,
                    onToggle = { active -> vm.setActive(row.monitor.id, active) },
                    onTap = { onMonitorTap(row.monitor.id) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ManageRow(
    row: ManageViewModel.Row,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val sub = listOfNotNull(
        row.monitor.type.uppercase(),
        row.monitor.hostname?.takeIf { it.isNotBlank() },
        row.monitor.url?.takeIf { it.isNotBlank() && it != "https://" },
    ).joinToString(" · ")

    KumaCard(onClick = onTap) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(row.status, size = 9.dp, pulse = row.status == app.kumacheck.data.model.MonitorStatus.UP)
            Spacer(Modifier.width(10.dp))
            TypeBadge(row.monitor.type)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.monitor.name,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = KumaTypography.body,
                    maxLines = 1,
                )
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.captionSmall,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = row.active,
                onCheckedChange = onToggle,
                enabled = !row.toggling,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = KumaTerra,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = KumaPaused,
                ),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = KumaSlate2)
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val short = when (type) {
        "real-browser" -> "BROW"
        "json-query" -> "JSON"
        "grpc-keyword" -> "GRPC"
        "tailscale-ping" -> "TS"
        "kafka-producer" -> "KAFKA"
        else -> type.take(5).uppercase()
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KumaCream2,
        border = BorderStroke(1.dp, KumaCardBorder),
    ) {
        Text(
            short,
            color = KumaSlate,
            fontFamily = KumaMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = KumaTypography.captionSmall,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
