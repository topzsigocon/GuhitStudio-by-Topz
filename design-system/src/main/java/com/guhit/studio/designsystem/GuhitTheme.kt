package com.guhit.studio.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Neutral dark palette: grey surfaces so the artwork's colors stay true. */
object GuhitColors {
    val Background = Color(0xFF1C1C1E)
    val Surface = Color(0xFF2C2C2E)
    val SurfaceHigh = Color(0xFF3A3A3C)
    val Accent = Color(0xFF4F8CFF)
    val OnSurface = Color(0xFFEDEDED)
}

private val DarkScheme = darkColorScheme(
    primary = GuhitColors.Accent,
    background = GuhitColors.Background,
    surface = GuhitColors.Surface,
    surfaceVariant = GuhitColors.SurfaceHigh,
    onBackground = GuhitColors.OnSurface,
    onSurface = GuhitColors.OnSurface,
)

@Composable
fun GuhitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
