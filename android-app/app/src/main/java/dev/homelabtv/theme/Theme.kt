package dev.homelabtv.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TV app: always dark, no dynamic color — matches the Jellyfin look on every device.
private val JellyfinDarkColorScheme =
  darkColorScheme(
    primary = JellyfinBlue,
    onPrimary = Color.White,
    secondary = JellyfinPurple,
    onSecondary = Color.White,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = SurfaceColor,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextSecondary,
  )

@Composable
fun HomelabTVTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = JellyfinDarkColorScheme, typography = Typography, content = content)
}
