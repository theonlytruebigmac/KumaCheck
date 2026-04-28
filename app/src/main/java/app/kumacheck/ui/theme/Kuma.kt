package app.kumacheck.ui.theme

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.MonitorStatus

// --- KumaCheck "cozy" design tokens ---
// Cards: white surface, 14dp corner radius, 1px hairline border at ~6% ink.
// Section headers: Geist Mono uppercase, slate-2, letter spacing 0.6sp.
// Status: dot indicators (with optional pulse), pill badges, 24h heatmap.

val KumaCardCorner = 14.dp
val KumaCardBorder: Color
    @Composable get() = LocalKumaColors.current.cardBorder

@Composable
fun statusColor(s: MonitorStatus): Color = when (s) {
    MonitorStatus.UP -> KumaUp
    MonitorStatus.DOWN -> KumaDown
    MonitorStatus.PENDING -> KumaWarn
    MonitorStatus.MAINTENANCE -> KumaSlate
    MonitorStatus.UNKNOWN -> KumaPaused
}

@Composable
fun statusBgColor(s: MonitorStatus): Color = when (s) {
    MonitorStatus.UP -> KumaUpBg
    MonitorStatus.DOWN -> KumaDownBg
    MonitorStatus.PENDING -> KumaWarnBg
    MonitorStatus.MAINTENANCE -> KumaPausedBg
    MonitorStatus.UNKNOWN -> KumaPausedBg
}

@Composable
fun KumaCard(
    modifier: Modifier = Modifier,
    corner: Dp = KumaCardCorner,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val base = modifier.fillMaxWidth()
    val surface = LocalKumaColors.current.surface
    if (onClick != null) {
        Surface(
            modifier = base,
            shape = shape,
            color = surface,
            border = BorderStroke(1.dp, KumaCardBorder),
            onClick = onClick,
        ) { Column(content = content) }
    } else {
        Surface(
            modifier = base,
            shape = shape,
            color = surface,
            border = BorderStroke(1.dp, KumaCardBorder),
        ) { Column(content = content) }
    }
}

@Composable
fun KumaAccentCard(
    accent: Color,
    modifier: Modifier = Modifier,
    corner: Dp = KumaCardCorner,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val surface = LocalKumaColors.current.surface
    val tinted = blend(accent.copy(alpha = 0.10f), surface)
    val base = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(
            modifier = base,
            shape = shape,
            color = tinted,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
            onClick = onClick,
        ) { Column(content = content) }
    } else {
        Surface(
            modifier = base,
            shape = shape,
            color = tinted,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
        ) { Column(content = content) }
    }
}

private fun blend(fg: Color, bg: Color): Color {
    val a = fg.alpha
    return Color(
        red = fg.red * a + bg.red * (1 - a),
        green = fg.green * a + bg.green * (1 - a),
        blue = fg.blue * a + bg.blue * (1 - a),
    )
}

@Composable
fun KumaSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KumaSlate2,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = color,
            fontFamily = KumaMono,
            fontSize = KumaTypography.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
fun StatusDot(
    status: MonitorStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    pulse: Boolean = false,
) {
    val color = statusColor(status)
    val outerSize = if (pulse && status == MonitorStatus.UP) size + 6.dp else size
    // A11Y1: tag the dot with a semantic content description so screen-reader
    // users get the status name. Color-blind users still rely on adjacent
    // text labels (which all StatusDot call sites already provide), but the
    // semantic anchor makes the dot itself meaningful in TalkBack flow.
    val statusLabel = when (status) {
        MonitorStatus.UP -> "up"
        MonitorStatus.DOWN -> "down"
        MonitorStatus.MAINTENANCE -> "maintenance"
        MonitorStatus.PENDING -> "pending"
        MonitorStatus.UNKNOWN -> "unknown"
    }
    Box(
        modifier = modifier
            .size(outerSize)
            .semantics { contentDescription = "status: $statusLabel" },
        contentAlignment = Alignment.Center,
    ) {
        if (pulse && status == MonitorStatus.UP) {
            val pulseAlpha by rememberPulse()
            Box(
                Modifier
                    .size(outerSize)
                    .alpha(pulseAlpha * 0.5f)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
        Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
    }
}

/**
 * Shared pulse alpha. Read by every [StatusDot] with `pulse = true` so the
 * whole screen drives one infinite animation instead of N — pre-fix, a
 * Monitors / Manage / Overview list with 60+ UP rows allocated 60+
 * `rememberInfiniteTransition`s, each producing a fresh alpha at 60 Hz,
 * which dominated the main thread during scroll.
 *
 * Default is a non-animating [androidx.compose.runtime.mutableStateOf]
 * so previews and unit tests don't need a CompositionLocal provider.
 * Real screens override via [PulseAlphaProvider] at the root.
 */
val LocalPulseAlpha = androidx.compose.runtime.staticCompositionLocalOf {
    androidx.compose.runtime.mutableFloatStateOf(1f) as androidx.compose.runtime.State<Float>
}

/**
 * Wrap the screen root in this to drive the single shared pulse animation
 * everyone reads via [LocalPulseAlpha]. One transition for the whole tree.
 */
@Composable
fun PulseAlphaProvider(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "kc-pulse")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPulseAlpha provides alpha) {
        content()
    }
}

@Composable
private fun rememberPulse(): androidx.compose.runtime.State<Float> = LocalPulseAlpha.current

@Composable
fun StatusBadge(
    status: MonitorStatus,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    label: String? = null,
) {
    val fg = statusColor(status)
    val bg = statusBgColor(status)
    val text = label ?: when (status) {
        MonitorStatus.UP -> "Operational"
        MonitorStatus.DOWN -> "Down"
        MonitorStatus.PENDING -> "Degraded"
        MonitorStatus.MAINTENANCE -> "Maintenance"
        MonitorStatus.UNKNOWN -> "Unknown"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (big) 14.dp else 10.dp,
                vertical = if (big) 7.dp else 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (big) 8.dp else 6.dp),
        ) {
            Box(
                Modifier
                    .size(if (big) 8.dp else 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(fg)
            )
            Text(
                text.uppercase(),
                color = fg,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (big) 12.sp else 10.sp,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

/**
 * 24h heat strip — fills the available width with a row of 1.5px-gapped cells
 * coloured by status. Used on Dashboard hero, Status Page rows, Incident
 * timeline.
 */
@Composable
fun StatusGrid(
    statuses: List<MonitorStatus>,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        statuses.forEach { s ->
            val cellAlpha = if (s == MonitorStatus.UNKNOWN) 0.3f else 1f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .alpha(cellAlpha)
                    .background(statusColor(s)),
            )
        }
    }
}
