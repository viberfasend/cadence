package de.andi1984.cadence.ui.settings

import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.sort_date
import de.andi1984.cadence.ui.resources.sort_importance
import de.andi1984.cadence.ui.resources.sort_manual
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import java.time.Instant

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** 64dp comfortable rows (1a) or 52dp compact rows (1b). */
enum class Density { COMFORTABLE, COMPACT }

enum class SortMode(val label: StringResource) {
    IMPORTANCE(Res.string.sort_importance),
    DATE(Res.string.sort_date),
    MANUAL(Res.string.sort_manual),
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

/**
 * Where the settings live, stated as a port for the same reason storage is one: Android has
 * `SharedPreferences` and the desktop will have a JSON file under `PlatformDirs`, and nothing
 * above this line should be able to tell which it got. Settings stay per-device and out of the
 * sync file — density, theme and language describe a screen, not a task list (ADR 0001,
 * decision 9).
 */
interface SettingsStore {

    val state: StateFlow<CadenceSettings>

    fun setTheme(theme: ThemeChoice)

    fun setDensity(density: Density)

    fun setSortMode(sortMode: SortMode)

    fun setShowCompleted(show: Boolean)

    // ── Automatic backup sync ──────────────────────────────────────────────────────

    /**
     * Switches sync on for [fileUri]. Naming a new file must drop the timestamp of the old one:
     * the app has never synced with *this* file, so it must not treat it as one of its own.
     */
    fun enableAutoBackup(fileUri: String)

    /** Keeps the file, so ticking the box again picks up where it left off. */
    fun disableAutoBackup()

    /** The user has been asked once; whatever they answered, don't ask again. */
    fun markAutoBackupOffered()

    /** Remembers the `exportedAt` of the file this device just wrote or read. */
    fun recordAutoBackupSync(at: Instant)
}
