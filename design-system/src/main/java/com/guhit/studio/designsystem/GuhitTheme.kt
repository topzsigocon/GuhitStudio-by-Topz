package com.guhit.studio.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Midnight blue + black, with a luxury gold accent. Neutral enough not to tint the artwork. */
object GuhitColors {
    val Ink = Color(0xFF05070D)          // near-black canvas surround
    val Navy900 = Color(0xFF0A1226)      // bars and sheets
    val Navy800 = Color(0xFF101C3A)      // raised surfaces, tracks
    val Navy700 = Color(0xFF182A52)      // outlines, pressed states
    val Gold = Color(0xFFD4AF37)         // accent
    val GoldLight = Color(0xFFF0D98A)
    val GoldDark = Color(0xFF8A6A1F)
    val Ivory = Color(0xFFEDE7D3)        // main text / icons
    val Mist = Color(0xFFA7B0C6)         // secondary text
}

private val DarkScheme = darkColorScheme(
    primary = GuhitColors.Gold,
    onPrimary = Color(0xFF1A1405),
    primaryContainer = GuhitColors.GoldDark,
    onPrimaryContainer = GuhitColors.GoldLight,
    secondary = GuhitColors.GoldLight,
    onSecondary = Color(0xFF1A1405),
    background = GuhitColors.Ink,
    onBackground = GuhitColors.Ivory,
    surface = GuhitColors.Navy900,
    onSurface = GuhitColors.Ivory,
    surfaceVariant = GuhitColors.Navy800,
    onSurfaceVariant = GuhitColors.Mist,
    outline = GuhitColors.Navy700,
    outlineVariant = GuhitColors.Navy700,
    surfaceContainerLowest = GuhitColors.Ink,
    surfaceContainerLow = GuhitColors.Navy900,
    surfaceContainer = GuhitColors.Navy900,
    surfaceContainerHigh = GuhitColors.Navy800,
    surfaceContainerHighest = GuhitColors.Navy700,
    error = Color(0xFFFF8A80),
)

@Composable
fun GuhitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
