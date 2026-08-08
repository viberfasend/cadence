package de.andi1984.cadence.data.settings

import android.content.Context
import de.andi1984.cadence.ui.settings.AutoBackupSettings
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Android's answer to [SettingsStore]: `SharedPreferences`, not DataStore — the whole file is
 *  four scalars and a uri, read once at startup and written on every change. */
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
        autoBackup = AutoBackupSettings(
            enabled = prefs.getBoolean(KEY_AUTO_BACKUP, false),
            fileUri = prefs.getString(KEY_AUTO_BACKUP_URI, null),
            offered = prefs.getBoolean(KEY_AUTO_BACKUP_OFFERED, false),
            lastSyncedAt = prefs.getLong(KEY_AUTO_BACKUP_SYNCED_AT, NEVER_SYNCED)
                .takeIf { it != NEVER_SYNCED }
                ?.let(Instant::ofEpochMilli),
        ),
    )

    private fun persist(settings: CadenceSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_DENSITY, settings.density.name)
            .putString(KEY_SORT, settings.sortMode.name)
            .putBoolean(KEY_SHOW_COMPLETED, settings.showCompleted)
            .putBoolean(KEY_AUTO_BACKUP, settings.autoBackup.enabled)
            .putString(KEY_AUTO_BACKUP_URI, settings.autoBackup.fileUri)
            .putBoolean(KEY_AUTO_BACKUP_OFFERED, settings.autoBackup.offered)
            .putLong(
                KEY_AUTO_BACKUP_SYNCED_AT,
                settings.autoBackup.lastSyncedAt?.toEpochMilli() ?: NEVER_SYNCED,
            )
            .apply()
        _state.value = settings
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    // ── Automatic backup sync ──────────────────────────────────────────────────────

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

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DENSITY = "density"
        const val KEY_SORT = "sort"
        const val KEY_SHOW_COMPLETED = "showCompleted"
        const val KEY_AUTO_BACKUP = "autoBackup"
        const val KEY_AUTO_BACKUP_URI = "autoBackupUri"
        const val KEY_AUTO_BACKUP_OFFERED = "autoBackupOffered"
        const val KEY_AUTO_BACKUP_SYNCED_AT = "autoBackupSyncedAt"
        const val NEVER_SYNCED = -1L
    }
}
