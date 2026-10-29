package com.chapulin.remoto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta sacada del logo (chapulin_remoto.png): verde hierba sobre azul marino.
val ChapulinGreen = Color(0xFF7CB342)
val ChapulinGreenDark = Color(0xFF558B2F)
val ChapulinNavy = Color(0xFF293B51)
val ChapulinSurface = Color(0xFF16202B)
val ChapulinSurfaceVariant = Color(0xFF1F2E3D)
val ChapulinOutline = Color(0xFF35495E)

private val ChapulinColors = darkColorScheme(
    primary = ChapulinGreen,
    onPrimary = Color(0xFF102000),
    primaryContainer = ChapulinGreenDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF9CCC65),
    onSecondary = Color(0xFF102000),
    background = ChapulinSurface,
    onBackground = Color(0xFFE6EDF3),
    surface = ChapulinSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = ChapulinSurfaceVariant,
    onSurfaceVariant = Color(0xFFB6C4D2),
    outline = ChapulinOutline,
    error = Color(0xFFEF5350),
)

@Composable
fun ChapulinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChapulinColors, content = content)
}
