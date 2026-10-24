package com.afeicn.aiplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Ink {
    val Violet = Color(0xFF8E64FA)
    val VioletLight = Color(0xFFA78BFA)
    val VioletMuted = Color(0xFFE8DEFE)
    val Charcoal = Color(0xFF111529)
    val Midnight = Color(0xFF10172C)
    val Slate = Color(0xFF6A7189)
    val Ice = Color(0xFFF6F6FF)
    val Danger = Color(0xFFE95D67)
    val Success = Color(0xFF36A77B)
}

private val lightPalette = lightColorScheme(
    primary = Ink.Violet,
    onPrimary = Color.White,
    primaryContainer = Ink.VioletMuted,
    onPrimaryContainer = Ink.Charcoal,
    background = Color(0xFFF5F5FD),
    onBackground = Color(0xFF14182B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B2D),
    surfaceVariant = Color(0xFFEDEEF8),
    onSurfaceVariant = Color(0xFF6B7187),
    outline = Color(0xFFDDE0EF),
    error = Ink.Danger
)
private val darkPalette = darkColorScheme(
    primary = Ink.VioletLight,
    onPrimary = Ink.Charcoal,
    primaryContainer = Color(0xFF332951),
    onPrimaryContainer = Color(0xFFE5D8FF),
    background = Color(0xFF0C1325),
    onBackground = Color(0xFFF6F6FF),
    surface = Color(0xFF182039),
    onSurface = Color(0xFFF4F3FF),
    surfaceVariant = Color(0xFF27304B),
    onSurfaceVariant = Color(0xFFB2BAD1),
    outline = Color(0xFF404964),
    error = Color(0xFFFF7E87)
)

@Composable
fun AIPlayerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkPalette else lightPalette,
        content = content
    )
}
