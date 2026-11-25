package com.apexhorizon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ApexColors = darkColorScheme(
    primary = Apex.CyberBlue,
    onPrimary = Apex.NavyDeep,
    secondary = Apex.NeonOrange,
    onSecondary = Apex.NavyDeep,
    tertiary = Apex.IceBlue,
    background = Apex.Navy,
    onBackground = Apex.TextPrimary,
    surface = Apex.Surface,
    onSurface = Apex.TextPrimary,
    surfaceVariant = Apex.SurfaceHigh,
    onSurfaceVariant = Apex.TextDim,
    outline = Apex.Outline,
    error = Apex.NeonOrange,
    onError = Apex.NavyDeep,
)

private val ApexTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 3.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp),
)

/** The app is always dark: a HUD must not blind the driver at dusk. */
@Composable
fun ApexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ApexColors, typography = ApexTypography, content = content)
}
