package com.radirsky.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TacticalOrange = Color(0xFFFF5A36)
val DarkBackground = Color(0xFF090C10)
val DarkSurface = Color(0xFF121720)
val TacticalGrid = Color(0xFF1E2634)
val RadarSweepColor = Color(0x33FF5A36)
val RadarGreen = Color(0xFF00FF66)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)

private val DarkColorScheme = darkColorScheme(
    primary = TacticalOrange,
    secondary = RadarGreen,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun RadirSkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = TacticalTypography,
        content = content
    )
}
