package app.kumacheck.ui.overview.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    // C8: filter once when the input list changes (NaN / infinity are
    // dropped because Skia silently rejects path segments with NaN
    // coordinates and leaves a half-rendered chart). Without `remember`
    // this allocated a fresh ArrayList on every recomposition — and with
    // 50+ sparklines on the overview that's a measurable per-frame churn
    // on lower-end devices.
    val cleaned = remember(points) { points.filter { it.isFinite() } }
    // Reuse the same Path instance across draws — `path.reset()` reclaims
    // the underlying segment buffer rather than allocating a new one.
    val path = remember { Path() }
    Canvas(modifier) {
        if (cleaned.size < 2) return@Canvas
        val minP = cleaned.min()
        val maxP = cleaned.max()
        // If everything is the same, render a flat horizontal line in the middle.
        val flat = (maxP - minP) < 1e-9
        val range = if (flat) 1.0 else (maxP - minP)
        val w = size.width
        val h = size.height
        val padTop = 2.dp.toPx()
        val padBottom = 2.dp.toPx()
        val drawH = h - padTop - padBottom

        path.reset()
        cleaned.forEachIndexed { i, p ->
            val x = if (cleaned.size == 1) w / 2f else w * i / (cleaned.size - 1)
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
