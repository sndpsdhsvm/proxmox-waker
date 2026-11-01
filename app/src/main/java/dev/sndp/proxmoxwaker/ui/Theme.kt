package dev.sndp.proxmoxwaker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The dark palette follows the current Omarchy theme ("mushroom"): near-black
// plum, warm off-white text, and a violet accent with cyan as the secondary.
private val MushroomBackground = Color(0xFF080312)
private val MushroomSurface = Color(0xFF0B0614)
private val MushroomSurfaceVariant = Color(0xFF171021)
private val MushroomForeground = Color(0xFFE9E6EF)
private val MushroomMuted = Color(0xFF9A97A2)
private val MushroomAccent = Color(0xFFD06FEE)
private val MushroomCyan = Color(0xFF20E7E4)
private val MushroomOrange = Color(0xFFFEA17F)

private val DarkColors = darkColorScheme(
    primary = MushroomAccent,
    onPrimary = Color(0xFF21052B),
    primaryContainer = Color(0xFF33133F),
    onPrimaryContainer = Color(0xFFF4D8FF),
    secondary = MushroomCyan,
    onSecondary = Color(0xFF002A29),
    secondaryContainer = Color(0xFF0C3231),
    onSecondaryContainer = Color(0xFFB6FFFC),
    tertiary = MushroomOrange,
    onTertiary = Color(0xFF3A1507),
    tertiaryContainer = Color(0xFF4A2315),
    onTertiaryContainer = Color(0xFFFFD9CA),
    background = MushroomBackground,
    onBackground = MushroomForeground,
    surface = MushroomSurface,
    onSurface = MushroomForeground,
    surfaceVariant = MushroomSurfaceVariant,
    onSurfaceVariant = MushroomMuted,
    outline = Color(0xFF3A2C4A),
    outlineVariant = Color(0xFF241A31),
    error = Color(0xFFF24E75),
    onError = Color(0xFF2B0410),
    errorContainer = Color(0xFF431021),
    onErrorContainer = Color(0xFFFFD9E0),
    inverseSurface = MushroomForeground,
    inverseOnSurface = Color(0xFF1B1520),
    inversePrimary = Color(0xFF7A2B95),
    scrim = Color.Black,
    surfaceTint = MushroomAccent,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A2BB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2D9FA),
    onPrimaryContainer = Color(0xFF300541),
    secondary = Color(0xFF006B6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB6F5F3),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFFB24B1A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCB),
    onTertiaryContainer = Color(0xFF3A0D00),
    background = Color(0xFFF8F5FA),
    onBackground = Color(0xFF1C1622),
    surface = Color.White,
    onSurface = Color(0xFF1C1622),
    surfaceVariant = Color(0xFFF0EAF4),
    onSurfaceVariant = Color(0xFF62586B),
    outline = Color(0xFF8B7E96),
    outlineVariant = Color(0xFFD9CFE0),
    error = Color(0xFFB3263F),
    onError = Color.White,
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF40000D),
    inverseSurface = Color(0xFF1C1622),
    inverseOnSurface = Color(0xFFF8F5FA),
    inversePrimary = Color(0xFFD06FEE),
    scrim = Color.Black,
    surfaceTint = Color(0xFF8A2BB0),
)

/** Accent colours for guest status dots. */
object StatusColors {
    val running = Color(0xFF6FCE33)
    val stopped = Color(0xFF8C8495)
    val warn = Color(0xFFFEE3C4)
}

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
