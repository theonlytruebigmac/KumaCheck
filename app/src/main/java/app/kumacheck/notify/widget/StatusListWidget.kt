package app.kumacheck.notify.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.kumacheck.MainActivity
import app.kumacheck.R
import app.kumacheck.data.model.MonitorStatus

class StatusListWidget : GlanceAppWidget() {
    override val stateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            GlanceTheme { ListContent(currentState()) }
        }
    }
}

@Composable
private fun ListContent(state: Preferences) {
    val total = state[SnapshotWriter.KEY_TOTAL] ?: 0
    val up = state[SnapshotWriter.KEY_UP] ?: 0
    val down = state[SnapshotWriter.KEY_DOWN] ?: 0
    val maintenance = state[SnapshotWriter.KEY_MAINTENANCE] ?: 0
    val rows = SnapshotWriter.decodeRows(state[SnapshotWriter.KEY_ROWS])

    val percent = if (total == 0) "—" else "${(up * 100) / total}%"

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.CREAM_2)
            .padding(8.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(Color.White)
                .padding(14.dp),
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(R.mipmap.ic_launcher),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = GlanceModifier.size(22.dp).cornerRadius(50.dp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        "KumaCheck",
                        style = TextStyle(
                            color = ColorProvider(WidgetColors.INK),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                Spacer(GlanceModifier.height(10.dp))
                Text(
                    "$up up · $down down".uppercase(),
                    style = TextStyle(
                        color = ColorProvider(WidgetColors.SLATE),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    percent,
                    style = TextStyle(
                        color = ColorProvider(WidgetColors.INK),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(GlanceModifier.defaultWeight())
                StatusGrid(up = up, warn = maintenance, down = down, total = total, cells = 24)
            }
            Spacer(GlanceModifier.width(10.dp))
            Box(
                modifier = GlanceModifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(WidgetColors.HAIRLINE),
            ) {}
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                val ranked = rows.sortedBy { rank(it.status) }.take(3)
                if (ranked.isEmpty()) {
                    Text(
                        "Open the app to connect",
                        style = TextStyle(
                            color = ColorProvider(WidgetColors.SLATE),
                            fontSize = 11.sp,
                        ),
                    )
                } else {
                    ranked.forEach { row -> WidgetRow(row) }
                }
            }
        }
    }
}

private fun rank(s: MonitorStatus): Int = when (s) {
    MonitorStatus.DOWN -> 0
    MonitorStatus.MAINTENANCE -> 1
    MonitorStatus.PENDING -> 2
    MonitorStatus.UP -> 3
    MonitorStatus.UNKNOWN -> 4
}

@Composable
private fun WidgetRow(row: StatusSnapshot.Row) {
    val color = when (row.status) {
        MonitorStatus.UP -> WidgetColors.UP
        MonitorStatus.DOWN -> WidgetColors.DOWN
        MonitorStatus.PENDING -> WidgetColors.WARN
        MonitorStatus.MAINTENANCE -> WidgetColors.SLATE
        MonitorStatus.UNKNOWN -> WidgetColors.SLATE
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .cornerRadius(50.dp)
                .background(ColorProvider(color)),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            row.name,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(WidgetColors.INK),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}
