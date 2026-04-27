package app.kumacheck.notify.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.kumacheck.MainActivity

/**
 * 1×1 micro tile — shows the most-glanceable summary: a status dot in the
 * accent color, a one-word label, and the up/total ratio. No sparkline, no
 * row list — meant to live in a corner of the launcher.
 */
class StatusMicroWidget : GlanceAppWidget() {
    override val stateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            GlanceTheme { MicroContent(currentState()) }
        }
    }
}

@Composable
private fun MicroContent(state: Preferences) {
    val total = state[SnapshotWriter.KEY_TOTAL] ?: 0
    val up = state[SnapshotWriter.KEY_UP] ?: 0
    val down = state[SnapshotWriter.KEY_DOWN] ?: 0
    val maintenance = state[SnapshotWriter.KEY_MAINTENANCE] ?: 0

    val accent: Color = when {
        total == 0 -> WidgetColors.SLATE
        down > 0 -> WidgetColors.DOWN
        maintenance > 0 -> WidgetColors.WARN
        else -> WidgetColors.UP
    }
    val label = when {
        total == 0 -> "—"
        down > 0 -> "$down"
        maintenance > 0 -> "$maintenance"
        else -> "OK"
    }
    val sub = if (total > 0) "$up/$total" else "wait"

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.CREAM_2)
            .padding(6.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(Color.White)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(14.dp)
                    .cornerRadius(50.dp)
                    .background(ColorProvider(accent)),
            ) {}
            Spacer(GlanceModifier.height(4.dp))
            Text(
                label,
                style = TextStyle(
                    color = ColorProvider(WidgetColors.INK),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                sub,
                style = TextStyle(
                    color = ColorProvider(WidgetColors.SLATE),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}
