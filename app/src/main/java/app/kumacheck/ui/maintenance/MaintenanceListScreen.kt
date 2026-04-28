package app.kumacheck.ui.maintenance

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.ui.theme.KumaCard
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaCream2
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSlate
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaUp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceListScreen(
    vm: MaintenanceListViewModel,
    onBack: () -> Unit,
    onMaintenanceTap: (Int) -> Unit,
    onCreateNew: () -> Unit,
) {
    val items by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Maintenance",
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
                    IconButton(onClick = onCreateNew) {
                        Icon(Icons.Filled.Add, contentDescription = "New maintenance")
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
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No maintenance windows scheduled",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(items, key = { it.id }) { m ->
                    // C10: stable per-row click lambda — see MonitorsScreen.
                    val onClick = remember(m.id, onMaintenanceTap) {
                        { onMaintenanceTap(m.id) }
                    }
                    MaintenanceRow(m, onClick = onClick)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MaintenanceRow(m: KumaSocket.Maintenance, onClick: () -> Unit) {
    KumaCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (m.active) KumaSlate.copy(alpha = 0.14f) else KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = if (m.active) KumaSlate else KumaSlate2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                    maxLines = 1,
                )
                val subtitle = listOfNotNull(m.status, m.strategy)
                    .joinToString(" · ")
                    .ifEmpty { if (m.active) "Active" else "Inactive" }
                Text(
                    subtitle.replaceFirstChar { it.titlecase() },
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                )
            }
            if (m.active) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = KumaUp.copy(alpha = 0.14f),
                ) {
                    Text(
                        "ACTIVE",
                        color = KumaUp,
                        fontFamily = KumaMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = KumaSlate2,
            )
        }
    }
}
