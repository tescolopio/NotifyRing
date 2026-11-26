package com.notifyring.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * High-contrast, senior-friendly Material 3 palette.
 * Targets WCAG AAA text contrast on both light and dark surfaces.
 */
private val SeniorLightColorScheme = lightColorScheme(
    primary = Color(0xFF0B3D91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001A44),
    secondary = Color(0xFF0F6E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2F5DF),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFF7A4100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE5C2),
    onTertiaryContainer = Color(0xFF2B1400),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0D1117),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1117),
    surfaceVariant = Color(0xFFE6ECF5),
    onSurfaceVariant = Color(0xFF1F2937),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF9CA3AF),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8EC),
    onErrorContainer = Color(0xFF4A000D)
)

private val SeniorDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF002B6B),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = Color(0xFFEAF2FF),
    secondary = Color(0xFF7CE8A8),
    onSecondary = Color(0xFF00381A),
    secondaryContainer = Color(0xFF0B532C),
    onSecondaryContainer = Color(0xFFD9FCE5),
    tertiary = Color(0xFFFFCA80),
    onTertiary = Color(0xFF422200),
    tertiaryContainer = Color(0xFF663600),
    onTertiaryContainer = Color(0xFFFFE9CC),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF3F6FA),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFF3F6FA),
    surfaceVariant = Color(0xFF222D3D),
    onSurfaceVariant = Color(0xFFD7E0EC),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF475569),
    error = Color(0xFFFF8A9B),
    onError = Color(0xFF5C0011),
    errorContainer = Color(0xFF7F0018),
    onErrorContainer = Color(0xFFFFE0E5)
)

@Immutable
data class NotifyRingStatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color
)

private val LightStatusColors = NotifyRingStatusColors(
    successContainer = Color(0xFFD4F5E0),
    onSuccessContainer = Color(0xFF063B1D),
    warningContainer = Color(0xFFFFF0C2),
    onWarningContainer = Color(0xFF4A2C00),
    dangerContainer = Color(0xFFFDE8EC),
    onDangerContainer = Color(0xFF6B0014)
)

private val DarkStatusColors = NotifyRingStatusColors(
    successContainer = Color(0xFF0B4625),
    onSuccessContainer = Color(0xFFD9FCE5),
    warningContainer = Color(0xFF5A3700),
    onWarningContainer = Color(0xFFFFE9BA),
    dangerContainer = Color(0xFF6E0014),
    onDangerContainer = Color(0xFFFFE0E5)
)

val LocalNotifyRingStatusColors = staticCompositionLocalOf { LightStatusColors }

val NotifyRingTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun NotifyRingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SeniorDarkColorScheme
        else -> SeniorLightColorScheme
    }
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalNotifyRingStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NotifyRingTypography,
            content = content
        )
    }
}
