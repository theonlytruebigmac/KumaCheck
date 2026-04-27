package app.kumacheck.ui.overview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kumacheck.data.model.Monitor
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaCream2
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaTerra

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinPickerSheet(
    monitors: List<Monitor>,
    pinnedIds: Set<Int>,
    onTogglePin: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = KumaCream,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live Heartbeat",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", color = KumaTerra, fontFamily = KumaFont, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                items(monitors, key = { it.id }) { m ->
                    val isPinned = m.id in pinnedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onTogglePin(m.id, !isPinned) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name,
                                color = KumaInk,
                                fontFamily = KumaFont,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            val sub = m.hostname
                                ?: m.url?.takeIf { it.isNotBlank() && it != "https://" }
                            if (sub != null) {
                                Text(
                                    sub,
                                    color = KumaSlate2,
                                    fontFamily = KumaMono,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier.size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isPinned) KumaTerra.copy(alpha = 0.18f)
                                    else KumaCream2
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isPinned) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Pinned",
                                    tint = KumaTerra,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Box(
                                    Modifier.size(20.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(KumaCardBorder),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
