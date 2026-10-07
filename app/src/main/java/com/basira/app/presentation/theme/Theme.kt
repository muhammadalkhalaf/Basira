package com.basira.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** High-contrast palette: yellow on black exceeds a 15:1 contrast ratio; white on black is 21:1. */
private val HighContrastColors = darkColorScheme(
    primary = Color(0xFFFFD600),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF332B00),
    onPrimaryContainer = Color(0xFFFFF3B0),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8FD8FF),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFEDEDED),
    outline = Color(0xFFFFFFFF),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF000000),
)

/** Large, scalable typography (sp units follow the system font scale). */
private val AccessibleTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
)

/**
 * Application theme. Always dark and high contrast because many low-vision users are glare sensitive;
 * Arabic RTL layout is applied automatically from the locale.
 *
 * @param content themed content.
 */
@Composable
fun BasiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HighContrastColors, typography = AccessibleTypography, content = content)
}
