package dev.sndp.proxmoxwaker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Proxmox's own orange, so the app feels like part of the stack.
private val ProxmoxOrange = Color(0xFFE57000)
private val ProxmoxOrangeLight = Color(0xFFFFB74D)
private val DeepSlate = Color(0xFF1F2430)
private val Slate = Color(0xFF2A3040)
private val Running = Color(0xFF4CAF50)
private val Stopped = Color(0xFF9E9E9E)
private val Warn = Color(0xFFFFB300)

private val DarkColors = darkColorScheme(
    primary = ProxmoxOrange,
    onPrimary = Color(0xFF241300),
    primaryContainer = Color(0xFF5A2D00),
    onPrimaryContainer = Color(0xFFFFD9B3),
    secondary = ProxmoxOrangeLight,
    background = DeepSlate,
    onBackground = Color(0xFFE6E8EE),
    surface = DeepSlate,
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Slate,
    onSurfaceVariant = Color(0xFFC2C7D4),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45300),
    onPrimary = Color.White,
    secondary = ProxmoxOrange,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

/** Accent colours for guest status chips. */
object StatusColors {
    val running = Running
    val stopped = Stopped
    val warn = Warn
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
