package de.andi1984.cadence.widget

import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.ui.theme.CadenceDarkColors
import de.andi1984.cadence.ui.theme.CadenceLightColors
import de.andi1984.cadence.ui.theme.DarkCadenceColors
import de.andi1984.cadence.ui.theme.LightCadenceColors

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

/**
 * The priority colours, from the same `CadenceColors` the app's `LocalCadenceColors` carries —
 * the M3 scheme has no slot for them, which is why [ColorProviders] cannot hand them over. The
 * completion ring wears these; the `P1`…`P4` text next to it is what keeps colour from standing
 * alone (`CLAUDE.md`, UI conventions).
 */
fun widgetPriorityColor(priority: Priority): ColorProvider = when (priority) {
    Priority.P1 -> ColorProvider(
        day = LightCadenceColors.priorityCritical,
        night = DarkCadenceColors.priorityCritical,
    )
    Priority.P2 -> ColorProvider(
        day = LightCadenceColors.priorityHigh,
        night = DarkCadenceColors.priorityHigh,
    )
    Priority.P3 -> ColorProvider(
        day = LightCadenceColors.priorityNormal,
        night = DarkCadenceColors.priorityNormal,
    )
    // Not `priorityTrack`: the track is the spine's unfilled background, nearly invisible as a
    // 2dp ring. P4 means "none", and the neutral variant colour is how the app writes "none".
    Priority.P4 -> ColorProvider(
        day = CadenceLightColors.onSurfaceVariant,
        night = CadenceDarkColors.onSurfaceVariant,
    )
}
