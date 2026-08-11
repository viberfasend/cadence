package de.andi1984.cadence.data.settings

import android.content.Context
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android's answer to [SettingsStore]: `SharedPreferences`, not DataStore — the whole file is
 *  a handful of scalars, read once at startup and written on every change. */
class SharedPrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("cadence-settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<CadenceSettings> = _state.asStateFlow()

    private fun load(): CadenceSettings = CadenceSettings(
        theme = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
            ?: ThemeChoice.SYSTEM,
        density = prefs.getString(KEY_DENSITY, null)
            ?.let { name -> Density.entries.firstOrNull { it.name == name } }
            ?: Density.COMFORTABLE,
        sortMode = prefs.getString(KEY_SORT, null)
            ?.let { name -> SortMode.entries.firstOrNull { it.name == name } }
            ?: SortMode.IMPORTANCE,
        showCompleted = prefs.getBoolean(KEY_SHOW_COMPLETED, true),
        // On by default here and off on the desktop: this is the device people carry, so it is
        // the one that should speak up when a task comes due (ADR 0002, decision 9).
        remindersEnabled = prefs.getBoolean(KEY_REMINDERS, true),
    )

    private fun persist(settings: CadenceSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_DENSITY, settings.density.name)
            .putString(KEY_SORT, settings.sortMode.name)
            .putBoolean(KEY_SHOW_COMPLETED, settings.showCompleted)
            .putBoolean(KEY_REMINDERS, settings.remindersEnabled)
            .apply()
        _state.value = settings
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    override fun setRemindersEnabled(enabled: Boolean) =
        persist(_state.value.copy(remindersEnabled = enabled))

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DENSITY = "density"
        const val KEY_SORT = "sort"
        const val KEY_SHOW_COMPLETED = "showCompleted"
        const val KEY_REMINDERS = "remindersEnabled"
    }
}
