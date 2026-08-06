package com.jakober.blockpdf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Markenfarben wie das Logo: Orange auf Creme, Gold als Akzent. */
private val Light = lightColorScheme(
    primary = Color(0xFFE7530E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9C2),
    onPrimaryContainer = Color(0xFF4A2408),
    secondary = Color(0xFFB58A1D),
    surface = Color(0xFFFDF8F2),
    background = Color(0xFFFDF8F2)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF3A1B05),
    primaryContainer = Color(0xFF6B3410),
    onPrimaryContainer = Color(0xFFFFD9C2),
    secondary = Color(0xFFEFB929)
)

@Composable
fun BlockPdfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
