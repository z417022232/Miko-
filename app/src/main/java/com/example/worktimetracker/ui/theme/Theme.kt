package com.example.worktimetracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    primaryContainer = Color(0xFFE8F0FF),
    secondary = Color(0xFF6D5DD3),
    secondaryContainer = Color(0xFFEEEAFE),
    background = Color(0xFFF4F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F3F8),
    onPrimary = Color.White,
    onBackground = Color(0xFF172033),
    onSurface = Color(0xFF172033),
    outline = Color(0xFFDCE2EC)
)

@Composable
fun WorkTimeTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
