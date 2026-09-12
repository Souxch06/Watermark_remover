package com.souxch.watermarkremover.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand palette: indigo -> violet gradient (same as the launcher icon), sky-blue accent,
// warm amber only for the selected zone so it stays readable on any video.
private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFF7C3AED)
private val VioletLight = Color(0xFFB4A5FF)
private val Sky = Color(0xFF0EA5E9)
private val SkyLight = Color(0xFF7DD3FC)
private val Amber = Color(0xFFFFB454)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF1B0A5C),
    secondary = Color(0xFF0369A1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F0FF),
    onSecondaryContainer = Color(0xFF002A3F),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3BF),
    onTertiaryContainer = Color(0xFF2E1800),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF17151F),
    surface = Color(0xFFF7F7FB),
    onSurface = Color(0xFF17151F),
    surfaceVariant = Color(0xFFE7E4F2),
    onSurfaceVariant = Color(0xFF5A5768),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F0F7),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEBF5),
    surfaceContainerHighest = Color(0xFFE5E2EF),
    outline = Color(0xFF8A8797),
    outlineVariant = Color(0xFFDDDAE8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFF24107A),
    primaryContainer = Color(0xFF4A31B8),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = SkyLight,
    onSecondary = Color(0xFF003549),
    secondaryContainer = Color(0xFF004C68),
    onSecondaryContainer = Color(0xFFC6E7FF),
    tertiary = Color(0xFFFFB95E),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF653E00),
    onTertiaryContainer = Color(0xFFFFDDB6),
    background = Color(0xFF0C0C14),
    onBackground = Color(0xFFE6E4EE),
    surface = Color(0xFF0C0C14),
    onSurface = Color(0xFFE6E4EE),
    surfaceVariant = Color(0xFF24222F),
    onSurfaceVariant = Color(0xFFB7B4C4),
    surfaceContainerLowest = Color(0xFF07070D),
    surfaceContainerLow = Color(0xFF13121B),
    surfaceContainer = Color(0xFF17161F),
    surfaceContainerHigh = Color(0xFF1E1D28),
    surfaceContainerHighest = Color(0xFF272531),
    outline = Color(0xFF7F7C8C),
    outlineVariant = Color(0xFF2E2C3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val AppTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Typography().titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Typography().labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Brand gradient (home header, "done" screen, primary call to action). */
fun brandGradient(): Brush = Brush.linearGradient(listOf(Indigo, Violet, Sky))

/** Accent colours used by the zone overlay (kept outside the scheme so they stay readable on video). */
object ZoneColors {
    val selected = Amber
    val idle = SkyLight
    val handleFill = Color.White
}

@Composable
fun WatermarkRemoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The brand palette is used on every device (no dynamic colours) so the app always looks
    // like its icon and the screenshots.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
