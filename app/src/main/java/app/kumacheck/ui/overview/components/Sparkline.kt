package app.kumacheck.ui.overview.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Tiny line chart for showing recent ping trend per monitor.
 * Renders nothing if [points] has fewer than 2 entries.
 */
@Composable
fun Sparkline(
    points: List<Double>,
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier) {
        // Drop NaN / infinity inputs first — Skia silently rejects path
        // segments with NaN coordinates, leaving a half-rendered chart.
        val points = points.filter { it.isFinite() }
        if (points.size < 2) return@Canvas
        val minP = points.min()
        val maxP = points.max()
        // If everything is the same, render a flat horizontal line in the middle.
        val flat = (maxP - minP) < 1e-9
        val range = if (flat) 1.0 else (maxP - minP)
        val w = size.width
        val h = size.height
        val padTop = 2.dp.toPx()
        val padBottom = 2.dp.toPx()
        val drawH = h - padTop - padBottom

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = if (points.size == 1) w / 2f else w * i / (points.size - 1)
            val y = if (flat) padTop + drawH / 2f
                else padTop + drawH - ((p - minP) / range * drawH).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
