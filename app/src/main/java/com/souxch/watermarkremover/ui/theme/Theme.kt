package com.souxch.watermarkremover.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand palette: deep teal + warm amber accent (the amber is also used for the selected zone).
private val Teal = Color(0xFF0B7A99)
private val TealLight = Color(0xFF5FD0F0)
private val Amber = Color(0xFFFFB454)
private val AmberDark = Color(0xFF8A4B00)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDF8),
    onPrimaryContainer = Color(0xFF00303D),
    secondary = AmberDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B5),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF5B5F7A),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF191C21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF4A5260),
    surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE2E7EF),
    outline = Color(0xFF7A8494),
    outlineVariant = Color(0xFFD2D9E3),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0B5A72),
    onPrimaryContainer = Color(0xFFBDEBFA),
    secondary = Amber,
    onSecondary = Color(0xFF3F2400),
    secondaryContainer = Color(0xFF5C3600),
    onSecondaryContainer = Color(0xFFFFDDB6),
    tertiary = Color(0xFFC3C6E5),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE4E7EE),
    surface = Color(0xFF11161F),
    onSurface = Color(0xFFE4E7EE),
    surfaceVariant = Color(0xFF1E2531),
    onSurfaceVariant = Color(0xFFB4BCC9),
    surfaceContainer = Color(0xFF161C26),
    surfaceContainerHigh = Color(0xFF1C2330),
    surfaceContainerHighest = Color(0xFF232B39),
    outline = Color(0xFF7E8798),
    outlineVariant = Color(0xFF2E3746),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5A0000),
    errorContainer = Color(0xFF7A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val AppTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Hero gradient used on the home header and the "done" screen. */
@Composable
fun heroGradient(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primary, c.primary.copy(alpha = 0.55f), c.secondary.copy(alpha = 0.35f)))
}

/** Accent colours used by the zone overlay (kept outside the scheme so they stay readable on video). */
object ZoneColors {
    val selected = Amber
    val idle = TealLight
    val handleFill = Color.White
}

@Composable
fun WatermarkRemoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You colours on Android 12+, brand palette elsewhere.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, typography = AppTypography, content = content)
}
