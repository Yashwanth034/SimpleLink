package com.simplelink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SimpleLinkColors = darkColorScheme(
    primary = Color(0xFF8EF0B1),
    onPrimary = Color(0xFF071009),
    background = Color(0xFF08090B),
    onBackground = Color(0xFFF5F7F8),
    surface = Color(0xFF12161B),
    onSurface = Color(0xFFF5F7F8),
    surfaceVariant = Color(0xFF191E24),
    onSurfaceVariant = Color(0xFFAAB3BE),
    outline = Color(0xFF343B44),
    error = Color(0xFFFF7B7B)
)

@Composable
fun SimpleLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SimpleLinkColors,
        content = content
    )
}
