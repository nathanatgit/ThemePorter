package com.nathanhanapps.nebulaThemePorter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    // A restrained Material Tonal Spot palette: blue-grey emphasis, neutral surfaces, no wallpaper chroma.
    primary = Color(0xFF4A5F8E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6F4),
    onPrimaryContainer = Color(0xFF263B66),
    secondary = Color(0xFF5B5F69),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E5EB),
    onSecondaryContainer = Color(0xFF42464F),
    tertiary = Color(0xFF5C6069),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1E5EA),
    onTertiaryContainer = Color(0xFF44484F),
    background = Color(0xFFF9F9FC),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFF9F9FC),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2E7),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF747780),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C5F4),
    onPrimary = Color(0xFF20365F),
    primaryContainer = Color(0xFF344A76),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFC4C6CE),
    onSecondary = Color(0xFF2E3036),
    secondaryContainer = Color(0xFF45474E),
    onSecondaryContainer = Color(0xFFE1E2E9),
    tertiary = Color(0xFFC5C7CF),
    onTertiary = Color(0xFF2F3137),
    tertiaryContainer = Color(0xFF46474E),
    onTertiaryContainer = Color(0xFFE2E2E9),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E7),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E7),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outline = Color(0xFF8E9099),
)

private val PorterShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val DefaultTypography = Typography()
private val PorterTypography = Typography(
    displaySmall = DefaultTypography.displaySmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = DefaultTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = DefaultTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun PorterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = PorterTypography,
        shapes = PorterShapes,
        content = content,
    )
}
