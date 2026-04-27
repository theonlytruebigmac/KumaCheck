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

class StatusTileWidget : GlanceAppWidget() {
    override val stateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            GlanceTheme { TileContent(currentState()) }
        }
    }
}

@Composable
private fun TileContent(state: Preferences) {
    val total = state[SnapshotWriter.KEY_TOTAL] ?: 0
    val up = state[SnapshotWriter.KEY_UP] ?: 0
    val down = state[SnapshotWriter.KEY_DOWN] ?: 0
    val maintenance = state[SnapshotWriter.KEY_MAINTENANCE] ?: 0

    val accent = when {
        total == 0 -> WidgetColors.SLATE
        down > 0 -> WidgetColors.DOWN
        maintenance > 0 -> WidgetColors.WARN
        else -> WidgetColors.UP
    }
    val eyebrow = when {
        total == 0 -> "Waiting"
        down > 0 -> "$down down"
        maintenance > 0 -> "Heads up"
        else -> "All up"
    }
    val countText = if (total == 0) "—" else "$up/$total"

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.CREAM_2)
            .padding(8.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(Color.White)
                .padding(14.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.mipmap.ic_launcher),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.size(22.dp).cornerRadius(50.dp),
                )
                Spacer(GlanceModifier.defaultWeight())
                Box(
                    modifier = GlanceModifier
                        .size(7.dp)
                        .cornerRadius(50.dp)
                        .background(ColorProvider(accent)),
                ) {}
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                eyebrow.uppercase(),
                style = TextStyle(
                    color = ColorProvider(WidgetColors.SLATE),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                countText,
                style = TextStyle(
                    color = ColorProvider(WidgetColors.INK),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(GlanceModifier.height(8.dp))
            val spark = SnapshotWriter.decodeSpark(state[SnapshotWriter.KEY_SPARK])
            if (spark.isNotEmpty()) {
                Sparkline(values = spark, accent = accent)
            } else {
                StatusGrid(up = up, warn = maintenance, down = down, total = total, cells = 16)
            }
        }
    }
}

@Composable
internal fun Sparkline(values: List<Int>, accent: Color) {
    val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val baseHeight = 22.dp
    Row(modifier = GlanceModifier.fillMaxWidth().height(baseHeight)) {
        values.forEachIndexed { i, v ->
            if (i > 0) Spacer(GlanceModifier.width(1.dp))
            // Map ping → bar height. Floor at 3.dp so zero-ping cells still render.
            val ratio = v.toFloat() / maxV
            val h = (3 + (ratio * 19).toInt()).dp
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(h)
                        .cornerRadius(2.dp)
                        .background(ColorProvider(accent.copy(alpha = 0.85f))),
                ) {}
            }
        }
    }
}

@Composable
internal fun StatusGrid(up: Int, warn: Int, down: Int, total: Int, cells: Int) {
    if (total == 0) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(8.dp)
                .cornerRadius(50.dp)
                .background(WidgetColors.HAIRLINE),
        ) {}
        return
    }
    val downCells = ((down.toFloat() / total) * cells).toInt().coerceIn(if (down > 0) 1 else 0, cells)
    val warnCells = ((warn.toFloat() / total) * cells).toInt()
        .coerceAtMost(cells - downCells)
        .coerceAtLeast(if (warn > 0 && cells - downCells > 0) 1 else 0)
    val upCells = (cells - downCells - warnCells).coerceAtLeast(0)

    Row(modifier = GlanceModifier.fillMaxWidth()) {
        repeat(upCells) { i ->
            if (i > 0) Spacer(GlanceModifier.width(2.dp))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(12.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(WidgetColors.UP)),
            ) {}
        }
        repeat(warnCells) {
            Spacer(GlanceModifier.width(2.dp))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(12.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(WidgetColors.WARN)),
            ) {}
        }
        repeat(downCells) {
            Spacer(GlanceModifier.width(2.dp))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(12.dp)
                    .cornerRadius(2.dp)
                    .background(ColorProvider(WidgetColors.DOWN)),
            ) {}
        }
    }
}

internal object WidgetColors {
    val CREAM_2 = Color(0xFFF6ECDA)
    val INK = Color(0xFF1F2A33)
    val SLATE = Color(0xFF6B8696)
    val UP = Color(0xFF3B8C5A)
    val DOWN = Color(0xFFC0392B)
    val WARN = Color(0xFFC77B22)
    val HAIRLINE = Color(0x141F2A33)
}
