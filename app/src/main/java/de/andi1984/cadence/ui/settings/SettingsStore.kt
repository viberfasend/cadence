package de.andi1984.cadence.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import de.andi1984.cadence.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** 64dp comfortable rows (1a) or 52dp compact rows (1b). */
enum class Density { COMFORTABLE, COMPACT }

enum class SortMode(@StringRes val label: Int) {
    IMPORTANCE(R.string.sort_importance),
    DATE(R.string.sort_date),
    MANUAL(R.string.sort_manual),
}

data class CadenceSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val density: Density = Density.COMFORTABLE,
    val sortMode: SortMode = SortMode.IMPORTANCE,
    val showCompleted: Boolean = true,
)

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cadence-settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CadenceSettings> = _state.asStateFlow()

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
    )

    private fun persist(settings: CadenceSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_DENSITY, settings.density.name)
            .putString(KEY_SORT, settings.sortMode.name)
            .putBoolean(KEY_SHOW_COMPLETED, settings.showCompleted)
            .apply()
        _state.value = settings
    }

    fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DENSITY = "density"
        const val KEY_SORT = "sort"
        const val KEY_SHOW_COMPLETED = "showCompleted"
    }
}
