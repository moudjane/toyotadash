package com.moudjane.toyotadash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark — no dynamic colors on a car dashboard.
private val DashColorScheme = darkColorScheme(
    primary          = DashAccent,
    onPrimary        = DashSpeedWhite,
    background       = DashBackground,
    onBackground     = DashSpeedWhite,
    surface          = DashSurface,
    onSurface        = DashStatText,
    surfaceVariant   = DashSurface,
    onSurfaceVariant = DashLabelText,
)

@Composable
fun ToyotaDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashColorScheme,
        typography  = Typography,
        content     = content,
    )
}
