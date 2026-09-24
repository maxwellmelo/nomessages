package dev.mx3.nomessages.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    background = Color(0xFFFAFBFB),
    onBackground = Color(0xFF1B1F22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1F22),
    surfaceVariant = Color(0xFFF4F6F7),
    onSurfaceVariant = Color(0xFF3A4147),
    outline = Color(0xFFC7CDD1),
    outlineVariant = Color(0xFFDDE2E5),
    primary = Color(0xFF23282C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DFB8),
    onPrimaryContainer = Color(0xFF1B1F22),
    secondary = Color(0xFFD98E2B),
    onSecondary = Color(0xFF1B1F22),
    tertiary = Color(0xFF2E7BB6),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF14171A),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF1B1F22),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF2E353A),
    onSurfaceVariant = Color(0xFFB7C0C6),
    outline = Color(0xFF4A5157),
    outlineVariant = Color(0xFF3A4147),
    primary = Color(0xFF3A4147),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A3419),
    onPrimaryContainer = Color(0xFFF3EDE3),
    secondary = Color(0xFFF2A83D),
    onSecondary = Color(0xFF14171A),
    tertiary = Color(0xFF6FB8E8),
    onTertiary = Color(0xFF05283C),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun NoMessagesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
