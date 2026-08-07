package de.andi1984.cadence.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import de.andi1984.cadence.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** 64dp comfortable rows (1a) or 52dp compact rows (1b). */
enum class Density { COMFORTABLE, COMPACT }

enum class SortMode(@StringRes val label: Int) {
    IMPORTANCE(R.string.sort_importance),
    DATE(R.string.sort_date),
    MANUAL(R.string.sort_manual),
}

/**
 * The standing answer to "keep this backup file up to date by yourself".
 *
 * [offered] is what keeps the question a one-off: it is set the first time the app asks, so a
 * user who said no is never asked again and turns the feature on from Settings instead.
 * [fileUri] outlives [enabled] on purpose — switching sync off and on again resumes the same
 * file rather than sending the user back through the file picker.
 * [lastSyncedAt] is the `exportedAt` of the last file this device wrote or read; automatic
 * import compares it against the file on disk (see
 * [de.andi1984.cadence.domain.backup.AutoBackupPolicy]).
 */
data class AutoBackupSettings(
    val enabled: Boolean = false,
    val fileUri: String? = null,
    val offered: Boolean = false,
    val lastSyncedAt: Instant? = null,
) {
    /** Sync can only run once the user has both said yes and named a file. */
    val isActive: Boolean get() = enabled && fileUri != null
}

data class CadenceSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val density: Density = Density.COMFORTABLE,
    val sortMode: SortMode = SortMode.IMPORTANCE,
    val showCompleted: Boolean = true,
    val autoBackup: AutoBackupSettings = AutoBackupSettings(),
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

    fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    // ── Automatic backup sync ──────────────────────────────────────────────────────

    /**
     * Switches sync on for [fileUri]. Naming a new file drops the timestamp of the old one:
     * the app has never synced with *this* file, so it must not treat it as one of its own.
     */
    fun enableAutoBackup(fileUri: String) = persistAutoBackup { current ->
        current.copy(
            enabled = true,
            fileUri = fileUri,
            offered = true,
            lastSyncedAt = current.lastSyncedAt.takeIf { current.fileUri == fileUri },
        )
    }

    /** Keeps the file, so ticking the box again picks up where it left off. */
    fun disableAutoBackup() = persistAutoBackup { it.copy(enabled = false) }

    /** The user has been asked once; whatever they answered, don't ask again. */
    fun markAutoBackupOffered() = persistAutoBackup { it.copy(offered = true) }

    /** Remembers the `exportedAt` of the file this device just wrote or read. */
    fun recordAutoBackupSync(at: Instant) = persistAutoBackup { it.copy(lastSyncedAt = at) }

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
