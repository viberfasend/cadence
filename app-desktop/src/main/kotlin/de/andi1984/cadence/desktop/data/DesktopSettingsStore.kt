package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The on-disk shape, kept separate from [CadenceSettings] so the app's own types never need a
 *  `@Serializable` annotation just to be written to a file. */
@Serializable
private data class SettingsFile(
    val theme: String = ThemeChoice.SYSTEM.name,
    val density: String = Density.COMFORTABLE.name,
    val sortMode: String = SortMode.IMPORTANCE.name,
    val showCompleted: Boolean = true,
    // Off by default on the desktop — see CadenceSettings.remindersEnabled. The default lives in
    // this file rather than in the shared data class precisely because it differs per shell.
    val remindersEnabled: Boolean = false,
)

/** The desktop's answer to [SettingsStore]: a JSON file under [PlatformDirs], read once at
 *  startup and rewritten whole on every change — the same handful of scalars as Android's
 *  `SharedPrefsSettingsStore`, just with a file instead of `SharedPreferences`. */
class DesktopSettingsStore(dataDir: File = PlatformDirs.dataDir()) : SettingsStore {

    private val file = File(dataDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<CadenceSettings> = _state.asStateFlow()

    private fun load(): CadenceSettings {
        val onDisk = runCatching {
            if (file.exists()) json.decodeFromString<SettingsFile>(file.readText()) else null
        }.getOrNull() ?: SettingsFile()
        return CadenceSettings(
            theme = ThemeChoice.entries.firstOrNull { it.name == onDisk.theme } ?: ThemeChoice.SYSTEM,
            density = Density.entries.firstOrNull { it.name == onDisk.density } ?: Density.COMFORTABLE,
            sortMode = SortMode.entries.firstOrNull { it.name == onDisk.sortMode } ?: SortMode.IMPORTANCE,
            showCompleted = onDisk.showCompleted,
            remindersEnabled = onDisk.remindersEnabled,
        )
    }

    private fun persist(settings: CadenceSettings) {
        val onDisk = SettingsFile(
            theme = settings.theme.name,
            density = settings.density.name,
            sortMode = settings.sortMode.name,
            showCompleted = settings.showCompleted,
            remindersEnabled = settings.remindersEnabled,
        )
        file.writeText(json.encodeToString(SettingsFile.serializer(), onDisk))
        _state.value = settings
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    override fun setRemindersEnabled(enabled: Boolean) =
        persist(_state.value.copy(remindersEnabled = enabled))
}
