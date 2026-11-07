package com.notlexi.nlfileeditor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colors shared by both themes. */
object NLColors {
    val Blue = Color(0xFF3D7BFF)
    val Purple = Color(0xFF8B4DFF)
    val Success = Color(0xFF2ED3A0)

    /** The neon blue -> purple accent used on buttons and highlights. */
    val gradient: Brush = Brush.horizontalGradient(listOf(Blue, Purple))
}

private val DarkScheme = darkColorScheme(
    primary = NLColors.Blue,
    secondary = NLColors.Purple,
    tertiary = NLColors.Success,
    background = Color(0xFF0A0E1F),
    surface = Color(0xFF121833),
    surfaceVariant = Color(0xFF1B2347),
    onBackground = Color(0xFFE8EBFF),
    onSurface = Color(0xFFE8EBFF),
    onSurfaceVariant = Color(0xFF9AA3C7),
    outline = Color(0xFF2C3563),
    error = Color(0xFFFF6B7A)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F63F0),
    secondary = Color(0xFF7A3CF0),
    tertiary = Color(0xFF14A37F),
    background = Color(0xFFF5F6FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECFF),
    onBackground = Color(0xFF0A0E1F),
    onSurface = Color(0xFF0A0E1F),
    onSurfaceVariant = Color(0xFF5B6489),
    outline = Color(0xFFC9CFEF),
    error = Color(0xFFD93B4D)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp)
)

@Composable
fun NLFileEditorTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
