package app.kumacheck.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Geometric bear mascot — original design (no copyrighted likeness).
 * All primitives (circles, ellipses, a stroked path for the mouth). Renders
 * inside a 200×200 logical viewBox and scales to whatever [size] the caller
 * provides.
 */
@Composable
fun KumaMark(
    size: Dp,
    modifier: Modifier = Modifier,
    fur: Color = KumaFur,
    furDark: Color = KumaFurDark,
    muzzle: Color = KumaCream,
    ink: Color = KumaInk,
    cheek: Color = KumaTerra,
    pulse: Boolean = false,
    ringColor: Color = KumaUp,
    showEars: Boolean = true,
) {
    Canvas(modifier = modifier.size(size)) {
        drawKumaMark(fur, furDark, muzzle, ink, cheek, pulse, ringColor, showEars)
    }
}

private fun DrawScope.drawKumaMark(
    fur: Color,
    furDark: Color,
    muzzle: Color,
    ink: Color,
    cheek: Color,
    pulse: Boolean,
    ringColor: Color,
    showEars: Boolean,
) {
    // 1 logical unit of the design's 200×200 viewBox.
    val unit = size.minDimension / 200f
    fun p(v: Float) = v * unit
    fun pos(x: Float, y: Float) = Offset(p(x), p(y))

    val furBrush = Brush.radialGradient(
        colorStops = arrayOf(0f to fur, 1f to furDark),
        center = pos(100f, 80f),
        radius = p(140f),
    )

    if (showEars) {
        drawCircle(furBrush, p(22f), pos(48f, 58f))
        drawCircle(furBrush, p(22f), pos(152f, 58f))
        drawCircle(cheek.copy(alpha = 0.85f), p(11f), pos(48f, 58f))
        drawCircle(cheek.copy(alpha = 0.85f), p(11f), pos(152f, 58f))
    }

    // Head
    drawCircle(furBrush, p(64f), pos(100f, 108f))

    // Muzzle (ellipse rx=34 ry=26 at 100,130)
    drawOval(
        color = muzzle,
        topLeft = pos(100f - 34f, 130f - 26f),
        size = Size(p(68f), p(52f)),
    )

    // Nose (ellipse rx=9 ry=6.5 at 100,118)
    drawOval(
        color = ink,
        topLeft = pos(100f - 9f, 118f - 6.5f),
        size = Size(p(18f), p(13f)),
    )

    // Mouth — two quadratic curves from (100,124) down to (92,134) and (108,134)
    val mouthPath = Path().apply {
        moveTo(p(100f), p(124f))
        quadraticBezierTo(p(100f), p(134f), p(92f), p(134f))
        moveTo(p(100f), p(124f))
        quadraticBezierTo(p(100f), p(134f), p(108f), p(134f))
    }
    drawPath(
        mouthPath,
        color = ink,
        style = Stroke(width = p(2.6f), cap = StrokeCap.Round),
    )

    // Eyes
    drawCircle(ink, p(5.5f), pos(76f, 100f))
    drawCircle(ink, p(5.5f), pos(124f, 100f))
    drawCircle(Color.White, p(1.6f), pos(77.5f, 98.5f))
    drawCircle(Color.White, p(1.6f), pos(125.5f, 98.5f))

    // Cheek blush
    drawCircle(cheek.copy(alpha = 0.4f), p(6f), pos(62f, 120f))
    drawCircle(cheek.copy(alpha = 0.4f), p(6f), pos(138f, 120f))

    if (pulse) {
        drawCircle(
            color = ringColor.copy(alpha = 0.9f),
            radius = p(76f),
            center = pos(100f, 108f),
            style = Stroke(width = p(4f)),
        )
        drawCircle(Color.White, p(11f), pos(148f, 60f))
        drawCircle(ringColor, p(11f - 3f), pos(148f, 60f))
    }
}

/** Simplified mark used for app icon / silhouette / notification small icon. */
@Composable
fun KumaMarkFlat(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = KumaInk,
) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 96f
        fun p(v: Float) = v * unit
        fun pos(x: Float, y: Float) = Offset(p(x), p(y))
        drawCircle(color, p(11f), pos(22f, 26f))
        drawCircle(color, p(11f), pos(74f, 26f))
        drawCircle(color, p(30f), pos(48f, 52f))
        // Eyes (knockout via white)
        drawCircle(Color.White, p(3f), pos(36f, 48f))
        drawCircle(Color.White, p(3f), pos(60f, 48f))
        // Nose
        drawOval(
            color = Color.White,
            topLeft = pos(48f - 4.5f, 58f - 3f),
            size = Size(p(9f), p(6f)),
        )
    }
}
