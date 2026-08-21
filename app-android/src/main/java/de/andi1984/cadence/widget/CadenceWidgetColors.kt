package de.andi1984.cadence.widget

import androidx.glance.material3.ColorProviders
import de.andi1984.cadence.ui.theme.CadenceDarkColors
import de.andi1984.cadence.ui.theme.CadenceLightColors

/**
 * The exact app palette, not a second copy of it: [ColorProviders] builds Glance's colour set
 * straight from the same M3 `ColorScheme` objects [de.andi1984.cadence.ui.theme.CadenceTheme]
 * hands `MaterialTheme` — a widget therefore never drifts from the app it belongs to.
 *
 * Deliberately not dynamic-color (`GlanceTheme.colors` with no arguments, Android 12+ wallpaper
 * colours): the app itself does not offer Material You, so a widget that did would be the one
 * surface whose colours the user cannot see or choose from inside Cadence.
 */
val CadenceWidgetColors = ColorProviders(light = CadenceLightColors, dark = CadenceDarkColors)
