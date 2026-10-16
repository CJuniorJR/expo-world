package br.com.expoworld.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import br.com.expoworld.core.ExplorerTheme

val LocalExplorerTheme = staticCompositionLocalOf { ExplorerTheme.GREEN }
fun themeColor(rgb: Int) = Color(0xFF000000.toInt() or rgb)
@Composable fun ExplorerAppearance(theme: ExplorerTheme, content: @Composable () -> Unit) {
    val base = if (theme.light) lightColorScheme() else darkColorScheme()
    val colors = base.copy(primary = themeColor(theme.accent), onPrimary = themeColor(theme.background),
        secondary = themeColor(theme.accent), onSecondary = themeColor(theme.background),
        secondaryContainer = themeColor(theme.tinted), onSecondaryContainer = themeColor(theme.text),
        background = themeColor(theme.background), onBackground = themeColor(theme.text),
        surface = themeColor(theme.background), onSurface = themeColor(theme.text),
        surfaceVariant = themeColor(theme.surface), onSurfaceVariant = themeColor(theme.muted),
        outline = themeColor(theme.track))
    CompositionLocalProvider(LocalExplorerTheme provides theme) { MaterialTheme(colorScheme = colors, content = content) }
}
