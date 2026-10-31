package com.chapulin.remoto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta mejorada: Verdes vivos + Azul marino oscuro
// Inspirada en el nuevo logo: tonos vibrantes en fondo oscuro
val ChapulinGreenLight = Color(0xFF9CCC65)      // Verde luminoso
val ChapulinGreen = Color(0xFF7CB342)           // Verde principal
val ChapulinGreenDark = Color(0xFF558B2F)       // Verde oscuro
val ChapulinNavy = Color(0xFF263238)            // Azul marino oscuro
val ChapulinNavyLight = Color(0xFF37475C)       // Azul marino medio
val ChapulinSurface = Color(0xFF1A232A)         // Fondo muy oscuro
val ChapulinSurfaceVariant = Color(0xFF24323A)  // Superficie variante
val ChapulinOutline = Color(0xFF455A64)         // Outline oscuro
val ChapulinTextLight = Color(0xFFE6EDF3)       // Texto claro
val ChapulinTextMuted = Color(0xFFB6C4D2)       // Texto atenuado

private val ChapulinColors = darkColorScheme(
    primary = ChapulinGreen,
    onPrimary = Color(0xFF102000),
    primaryContainer = ChapulinGreenDark,
    onPrimaryContainer = ChapulinTextLight,
    secondary = ChapulinGreenLight,
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = ChapulinGreenDark,
    onSecondaryContainer = ChapulinTextLight,
    tertiary = ChapulinNavyLight,
    onTertiary = ChapulinTextLight,
    background = ChapulinSurface,
    onBackground = ChapulinTextLight,
    surface = ChapulinSurface,
    onSurface = ChapulinTextLight,
    surfaceVariant = ChapulinSurfaceVariant,
    onSurfaceVariant = ChapulinTextMuted,
    outline = ChapulinOutline,
    outlineVariant = ChapulinNavyLight,
    error = Color(0xFFEF5350),
    onError = Color(0xFF1A1A1A),
)

@Composable
fun ChapulinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChapulinColors, content = content)
}
