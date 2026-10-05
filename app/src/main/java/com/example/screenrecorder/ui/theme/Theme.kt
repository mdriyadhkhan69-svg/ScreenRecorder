package com.example.screenrecorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SrColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    secondary = Color(0xFF8B5CF6),
    onSecondary = Color.White,
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF141B2D),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1C2540),
    onSurfaceVariant = Color(0xFFA3ABBD),
    error = Color(0xFFEF4444),
    onError = Color.White,
    outline = Color(0xFF3A4466)
)

@Composable
fun ScreenRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SrColorScheme, content = content)
}