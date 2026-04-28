package app.kumacheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kumacheck.R

/**
 * Full slot table for the cozy palette. Two instances exist — one for light
 * mode, one for dark — and the active one is provided through
 * [LocalKumaColors]. The convenience top-level vals (`KumaCream`, `KumaInk`,
 * …) are `@Composable` getters that read from the local, so existing call
 * sites continue to work but now resolve per-theme.
 */
data class KumaColors(
    // Surfaces
    val cream: Color,
    val cream2: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val background: Color,
    val cardBorder: Color,
    // Foreground / text
    val ink: Color,
    val slate: Color,
    val slate2: Color,
    // Brand accents
    val terra: Color,
    val terra2: Color,
    val honey: Color,
    val fur: Color,
    val furDark: Color,
    // Status pairs
    val up: Color,
    val upBg: Color,
    val down: Color,
    val downBg: Color,
    val warn: Color,
    val warnBg: Color,
    val paused: Color,
    val pausedBg: Color,
)

val LightKumaColors = KumaColors(
    cream = Color(0xFFFFF8EE),
    cream2 = Color(0xFFF6ECDA),
    surface = Color.White,
    surfaceElevated = Color(0xFFF6F2EA),
    background = Color(0xFFFFF8EE),
    cardBorder = Color(0x0F1F2A33), // ~6% ink
    ink = Color(0xFF1F2A33),
    slate = Color(0xFF3B5B6B),
    // Darkened from #6B8696 → #557080 for WCAG AA contrast (≥4.5:1) against
    // KumaCream #FFF8EE — the prior value was ~3.2:1 and failed for any body
    // text under 18sp (subtitles, metadata, About values etc.).
    slate2 = Color(0xFF557080),
    terra = Color(0xFFD97757),
    terra2 = Color(0xFFB85B3F),
    honey = Color(0xFFE8B84A),
    fur = Color(0xFF4A3528),
    furDark = Color(0xFF2A1D14),
    up = Color(0xFF3B8C5A),
    upBg = Color(0xFFE5F1E9),
    down = Color(0xFFC0392B),
    downBg = Color(0xFFF7E2DE),
    // Darkened from #C77B22 → #A86518 for WCAG AA contrast against
    // KumaWarnBg #FAEED5 — the prior pair was ~2.88:1 and failed the 3:1
    // minimum for UI components (StatusBadge "Degraded", warning banners,
    // incident chips). #A86518 lands at ~5.3:1.
    warn = Color(0xFFA86518),
    warnBg = Color(0xFFFAEED5),
    paused = Color(0xFF6B8696),
    pausedBg = Color(0xFFE6ECEF),
)

// Dark palette: a cozy night version that holds the brand identity (terra,
// honey) but inverts surfaces. Backgrounds are warm-tinted near-black; text
// is a creamy off-white so the page still feels warm rather than clinical.
val DarkKumaColors = KumaColors(
    cream = Color(0xFF161B20),       // deep background
    cream2 = Color(0xFF1F262E),      // slightly elevated tint
    surface = Color(0xFF1F262E),     // card background
    surfaceElevated = Color(0xFF262E37),
    background = Color(0xFF161B20),
    cardBorder = Color(0x1FFFFFFF),  // ~12% white
    ink = Color(0xFFF0E8DC),         // warm off-white text
    slate = Color(0xFFB4C5D1),
    slate2 = Color(0xFF8DA1AE),
    terra = Color(0xFFE58C6A),       // slightly brighter for dark contrast
    terra2 = Color(0xFFC0664A),
    honey = Color(0xFFE8B84A),
    fur = Color(0xFFC8B59E),
    furDark = Color(0xFF8E7E68),
    up = Color(0xFF4DAB72),
    upBg = Color(0xFF1F2D24),
    down = Color(0xFFE55B4B),
    downBg = Color(0xFF2D1F1B),
    warn = Color(0xFFDB9844),
    warnBg = Color(0xFF2D261A),
    paused = Color(0xFF8DA1AE),
    pausedBg = Color(0xFF1F262E),
)

val LocalKumaColors = staticCompositionLocalOf { LightKumaColors }

// --- @Composable getter aliases ---
// Keep the existing names so the dozens of call sites that already say
// `color = KumaCream` keep working — they now resolve per-theme via the
// composition local.
val KumaCream: Color
    @Composable get() = LocalKumaColors.current.cream
val KumaCream2: Color
    @Composable get() = LocalKumaColors.current.cream2
val KumaInk: Color
    @Composable get() = LocalKumaColors.current.ink
val KumaSlate: Color
    @Composable get() = LocalKumaColors.current.slate
val KumaSlate2: Color
    @Composable get() = LocalKumaColors.current.slate2
val KumaTerra: Color
    @Composable get() = LocalKumaColors.current.terra
val KumaTerra2: Color
    @Composable get() = LocalKumaColors.current.terra2
val KumaHoney: Color
    @Composable get() = LocalKumaColors.current.honey
val KumaFur: Color
    @Composable get() = LocalKumaColors.current.fur
val KumaFurDark: Color
    @Composable get() = LocalKumaColors.current.furDark

val KumaUp: Color
    @Composable get() = LocalKumaColors.current.up
val KumaUpBg: Color
    @Composable get() = LocalKumaColors.current.upBg
val KumaDown: Color
    @Composable get() = LocalKumaColors.current.down
val KumaDownBg: Color
    @Composable get() = LocalKumaColors.current.downBg
val KumaWarn: Color
    @Composable get() = LocalKumaColors.current.warn
val KumaWarnBg: Color
    @Composable get() = LocalKumaColors.current.warnBg
val KumaPaused: Color
    @Composable get() = LocalKumaColors.current.paused
val KumaPausedBg: Color
    @Composable get() = LocalKumaColors.current.pausedBg

// Surface aliases — these aren't legacy, they're useful primary names that
// every screen reads to draw cards / dialogs / scaffolds.
val KumaSurface: Color
    @Composable get() = LocalKumaColors.current.surface
val KumaSurfaceElevated: Color
    @Composable get() = LocalKumaColors.current.surfaceElevated
val KumaBackground: Color
    @Composable get() = LocalKumaColors.current.background

// --- Typography ---
// The design pack specifies Geist for UI and Geist Mono for stats/timestamps.
// We bundle the four weights actually used across the app (400/500/600/700);
// other weights resolve to the closest match via Compose's font picker.
val KumaFont: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val KumaMono: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/**
 * TH3: shared typography size tokens. Grep for `fontSize = ` across the
 * UI shows ~25 inline sites with a small pool of recurring values
 * (10 / 11 / 12 / 13 / 14 / 16 / 18 / 24 / 28 sp). New screens should
 * reach for these names; older sites can migrate incrementally without
 * blocking PRs.
 */
object KumaTypography {
    /** Stat labels and small captions (e.g. "PINGED 12s ago"). */
    val captionSmall = 10.sp
    /** Default labels / monitor list metadata. */
    val caption = 11.sp
    /** Picker subheaders, secondary metadata. */
    val captionLarge = 12.sp
    /** Body — default text content. */
    val body = 13.sp
    /** Body emphasis / settings titles. */
    val bodyEmphasis = 14.sp
    /** Section headers / monitor names. */
    val title = 16.sp
    /** Sub-display headers. */
    val titleLarge = 18.sp
    /** Stat numbers (uptime %, ping ms). */
    val statNumber = 24.sp
    /** Top-of-screen titles (Overview "Good morning", etc.). */
    val display = 28.sp
}

@Composable
private fun lightScheme(c: KumaColors) = lightColorScheme(
    primary = c.terra,
    onPrimary = Color.White,
    secondary = c.slate,
    onSecondary = Color.White,
    tertiary = c.honey,
    onTertiary = c.ink,
    background = c.background,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.cream2,
    onSurfaceVariant = c.slate,
    outline = c.cardBorder,
    outlineVariant = c.cardBorder,
    error = c.down,
    onError = Color.White,
)

@Composable
private fun darkScheme(c: KumaColors) = darkColorScheme(
    primary = c.terra,
    onPrimary = c.ink,
    secondary = c.slate2,
    onSecondary = c.ink,
    tertiary = c.honey,
    onTertiary = c.ink,
    background = c.background,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surfaceElevated,
    onSurfaceVariant = c.slate,
    outline = c.cardBorder,
    outlineVariant = c.cardBorder,
    error = c.down,
    onError = Color.White,
)

@Composable
fun KumaTheme(useDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (useDark) DarkKumaColors else LightKumaColors
    val scheme = if (useDark) darkScheme(colors) else lightScheme(colors)
    CompositionLocalProvider(LocalKumaColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
