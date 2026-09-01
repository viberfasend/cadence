package de.andi1984.cadence.ui.settings

import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.sort_date
import de.andi1984.cadence.ui.resources.sort_importance
import de.andi1984.cadence.ui.resources.sort_manual
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** 64dp comfortable rows (1a) or 52dp compact rows (1b). */
enum class Density { COMFORTABLE, COMPACT }

enum class SortMode(val label: StringResource) {
    IMPORTANCE(Res.string.sort_importance),
    DATE(Res.string.sort_date),
    MANUAL(Res.string.sort_manual),
}

data class CadenceSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val density: Density = Density.COMFORTABLE,
    val sortMode: SortMode = SortMode.IMPORTANCE,
    val showCompleted: Boolean = true,
    /**
     * Whether *this* device fires reminders.
     *
     * A per-device switch because a task now exists on both devices: without it the phone's
     * notification and the desktop's tray balloon both go off for the same task at the same
     * minute (ADR 0002, decision 9). The default differs per shell — on for Android, off for the
     * desktop — which is why it is the store, not this data class, that decides it.
     *
     * The alternative, a synced "already fired" marker, needs both devices online exactly when a
     * reminder is due, which is when the desktop is most likely closed. A missed reminder is
     * worse than a doubled one.
     */
    val remindersEnabled: Boolean = true,
    /**
     * How long before a task's [de.andi1984.cadence.domain.model.Task.dueTime] this device fires
     * a notification — one per entry, so `[20, 10, 5]` posts three. Independent of
     * [de.andi1984.cadence.domain.model.Task.reminderTime], which keeps firing at its own exact
     * moment for a task that sets one instead of, or alongside, a due time.
     *
     * Empty by default rather than seeded with a preset: a fresh install (or an existing
     * install's tasks that already carry a due time) must not suddenly start notifying for
     * something nobody asked for. The reminder screen's job is choosing at least one.
     */
    val reminderLeadMinutes: List<Int> = emptyList(),
)

/**
 * Where the settings live, stated as a port for the same reason storage is one: Android has
 * `SharedPreferences` and the desktop a JSON file under `PlatformDirs`, and nothing above this
 * line should be able to tell which it got. Settings stay per-device and out of sync — density,
 * theme, language and now reminders describe a screen or a machine, not a task list (ADR 0001,
 * decision 9).
 */
interface SettingsStore {

    val state: StateFlow<CadenceSettings>

    fun setTheme(theme: ThemeChoice)

    fun setDensity(density: Density)

    fun setSortMode(sortMode: SortMode)

    fun setShowCompleted(show: Boolean)

    fun setRemindersEnabled(enabled: Boolean)

    fun setReminderLeadMinutes(minutes: List<Int>)
}
