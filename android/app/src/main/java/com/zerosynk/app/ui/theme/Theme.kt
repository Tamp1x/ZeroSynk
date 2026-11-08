package com.zerosynk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zerosynk.app.design.Design

val Accent = Color(0xFF0A84FF)
val Green = Color(0xFF34C759)
val Orange = Color(0xFFFF9500)
val Red = Color(0xFFFF3B30)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color.White,
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF3C3C43).copy(alpha = 0.6f),
    outline = Color(0xFF3C3C43).copy(alpha = 0.18f),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFEBEBF5).copy(alpha = 0.6f),
    outline = Color(0xFF545458).copy(alpha = 0.65f),
)

// Onyx: near-black surfaces with a gold accent (matches the PC skin).
private val OnyxColors = darkColorScheme(
    primary = Color(0xFFC9A227),
    onPrimary = Color(0xFF171205),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF6EFDF),
    surface = Color(0xFF17140D),
    onSurface = Color(0xFFF6EFDF),
    surfaceVariant = Color(0xFF242017),
    onSurfaceVariant = Color(0xFFA99E85),
    outline = Color(0xFFC9A227).copy(alpha = 0.5f),
)

// Eclipx: eclipse — dark grey/black with white outlines, almost no bright colors.
private val EclipxColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0D0D0D),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFFFFFFFF).copy(alpha = 0.7f),
)

val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp),
)

@Composable
fun ZeroSynkTheme(design: String = Design.current, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when (design) {
        Design.ONYX -> OnyxColors
        Design.ECLIPX -> EclipxColors
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
