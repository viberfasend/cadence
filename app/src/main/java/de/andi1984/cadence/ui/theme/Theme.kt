package de.andi1984.cadence.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.ThemeChoice

/** Row metrics for the two densities the design specifies: 64dp comfortable, 52dp compact. */
data class CadenceDensity(
    val rowMinHeight: Dp,
    val rowVerticalPadding: Dp,
    val showMetaLine: Boolean,
    val checkboxSize: Dp,
)

val ComfortableDensity = CadenceDensity(
    rowMinHeight = 64.dp,
    rowVerticalPadding = 12.dp,
    showMetaLine = true,
    checkboxSize = 24.dp,
)

val CompactDensity = CadenceDensity(
    rowMinHeight = 52.dp,
    rowVerticalPadding = 6.dp,
    showMetaLine = false,
    checkboxSize = 22.dp,
)

val LocalCadenceDensity = staticCompositionLocalOf { ComfortableDensity }

@Composable
fun CadenceTheme(
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    density: Density = Density.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val colorScheme = if (dark) CadenceDarkColors else CadenceLightColors
    val extendedColors = if (dark) DarkCadenceColors else LightCadenceColors
    val densityMetrics = when (density) {
        Density.COMFORTABLE -> ComfortableDensity
        Density.COMPACT -> CompactDensity
    }

    CompositionLocalProvider(
        LocalCadenceColors provides extendedColors,
        LocalCadenceDensity provides densityMetrics,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CadenceTypography,
            content = content,
        )
    }
}
