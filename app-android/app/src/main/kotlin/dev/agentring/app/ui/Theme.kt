package dev.agentring.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Hivepal "azul eléctrico": electric blue and cyan on near-black navy. Always dark (the user's choice),
// dynamic color off so the brand stays recognisable.
object Retro {
    val Ink = Color(0xFF000814)
    val Navy = Color(0xFF001D3D)
    val Deep = Color(0xFF00111F)
    val Blue = Color(0xFF00A6FB)
    val Cyan = Color(0xFF7DF9FF)
    val Royal = Color(0xFF0353A4)
    val Text = Color(0xFFE6F4FF)
    val Muted = Color(0xFF8FB3D9)
}

private val Dark = darkColorScheme(
    primary = Retro.Blue,
    onPrimary = Color(0xFF00111F),
    primaryContainer = Color(0xFF003566),
    onPrimaryContainer = Color(0xFFBDE6FF),
    secondary = Retro.Cyan,
    onSecondary = Color(0xFF001A1F),
    secondaryContainer = Color(0xFF00284D),
    onSecondaryContainer = Color(0xFFD6F6FF),
    tertiary = Retro.Cyan,
    onTertiary = Color(0xFF001A1F),
    background = Retro.Ink,
    onBackground = Retro.Text,
    surface = Retro.Ink,
    onSurface = Retro.Text,
    surfaceVariant = Color(0xFF00203F),
    onSurfaceVariant = Retro.Muted,
    surfaceContainerLowest = Color(0xFF00050E),
    surfaceContainerLow = Color(0xFF000D1E),
    surfaceContainer = Color(0xFF001127),
    surfaceContainerHigh = Retro.Navy,
    surfaceContainerHighest = Color(0xFF00264D),
    outline = Color(0xFF1F4E7A),
    outlineVariant = Color(0xFF0E2A47),
    error = Color(0xFFFF6B8B),
    onError = Color(0xFF3B0011),
    errorContainer = Color(0xFF4A0B1E),
    onErrorContainer = Color(0xFFFFD9E0),
)

/** Status colours that Material's scheme has no slot for. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val bad: Color, val idle: Color)

private val DarkStatus = StatusColors(ok = Color(0xFF3DFFA8), warn = Color(0xFFFFD60A), bad = Color(0xFFFF6B8B), idle = Color(0xFF4F6D8F))

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.copy(lineHeight = 24.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)

@Composable
fun AgentringTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides DarkStatus) {
        MaterialTheme(colorScheme = Dark, typography = Type, shapes = AppShapes, content = content)
    }
}
