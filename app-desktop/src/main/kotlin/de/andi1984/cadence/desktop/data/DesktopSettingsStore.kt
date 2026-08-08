package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.ui.settings.AutoBackupSettings
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
import java.time.Instant

/** The on-disk shape, kept separate from [CadenceSettings] so the app's own types never need a
 *  `@Serializable` annotation just to be written to a file. */
@Serializable
private data class SettingsFile(
    val theme: String = ThemeChoice.SYSTEM.name,
    val density: String = Density.COMFORTABLE.name,
    val sortMode: String = SortMode.IMPORTANCE.name,
    val showCompleted: Boolean = true,
    val autoBackupEnabled: Boolean = false,
    val autoBackupFilePath: String? = null,
    val autoBackupOffered: Boolean = false,
    val autoBackupLastSyncedAtMillis: Long? = null,
)

/** The desktop's answer to [SettingsStore]: a JSON file under [PlatformDirs], read once at
 *  startup and rewritten whole on every change — the same "four scalars and a uri" shape as
 *  Android's `SharedPrefsSettingsStore`, just with a file instead of `SharedPreferences`. */
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
            autoBackup = AutoBackupSettings(
                enabled = onDisk.autoBackupEnabled,
                fileUri = onDisk.autoBackupFilePath,
                offered = onDisk.autoBackupOffered,
                lastSyncedAt = onDisk.autoBackupLastSyncedAtMillis?.let(Instant::ofEpochMilli),
            ),
        )
    }

    private fun persist(settings: CadenceSettings) {
        val onDisk = SettingsFile(
            theme = settings.theme.name,
            density = settings.density.name,
            sortMode = settings.sortMode.name,
            showCompleted = settings.showCompleted,
            autoBackupEnabled = settings.autoBackup.enabled,
            autoBackupFilePath = settings.autoBackup.fileUri,
            autoBackupOffered = settings.autoBackup.offered,
            autoBackupLastSyncedAtMillis = settings.autoBackup.lastSyncedAt?.toEpochMilli(),
        )
        file.writeText(json.encodeToString(SettingsFile.serializer(), onDisk))
        _state.value = settings
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    override fun enableAutoBackup(fileUri: String) = persistAutoBackup { current ->
        current.copy(
            enabled = true,
            fileUri = fileUri,
            offered = true,
            lastSyncedAt = current.lastSyncedAt.takeIf { current.fileUri == fileUri },
        )
    }

    override fun disableAutoBackup() = persistAutoBackup { it.copy(enabled = false) }

    override fun markAutoBackupOffered() = persistAutoBackup { it.copy(offered = true) }

    override fun recordAutoBackupSync(at: Instant) =
        persistAutoBackup { it.copy(lastSyncedAt = at) }

    private fun persistAutoBackup(change: (AutoBackupSettings) -> AutoBackupSettings) {
        val current = _state.value
        persist(current.copy(autoBackup = change(current.autoBackup)))
    }
}
