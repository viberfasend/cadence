package de.andi1984.cadence.ui.platform

import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.StateFlow

/**
 * What the ViewModel needs from the machine it is running on.
 *
 * `:core` already states storage as a port rather than a layer; these are the same idea for
 * everything else the app touches that Android and the desktop do differently — alarms, the file
 * a backup is written to, the folder that keeps two devices in step. `:ui` declares them, the
 * shells implement them (`:app` with AlarmManager and the Storage Access Framework today,
 * `:app-desktop` with a timer and `java.nio` in phase 5), and no screen learns which it got.
 */

/**
 * A file the user has named for a backup. A SAF content uri on Android, a path on the desktop —
 * either way it is an opaque string the shell knows how to reopen, and the only thing `:ui` does
 * with it is hand it back.
 */
@JvmInline
value class BackupTarget(val value: String)

/** Brings the platform's scheduled reminders in line with the tasks that still exist. */
interface ReminderScheduler {
    /** Every task in [tasks] is either (re)scheduled or cancelled, so removing a reminder takes
     *  effect on the next sync. */
    fun sync(tasks: List<Task>)

    /** Drops a task's reminder outright — for a row that is about to stop existing, which
     *  [sync] would never see again. */
    fun cancel(taskId: String)
}

/** Reads and writes a single backup file the user named. */
interface BackupGateway {
    suspend fun export(target: BackupTarget): BackupOutcome

    suspend fun import(target: BackupTarget): BackupOutcome
}

/**
 * The "keep this file up to date by yourself" feature, as the ViewModel sees it. The rules about
 * *when* it writes and reads are the implementation's; this is only the switch and the health
 * light. Superseded in phase 6 by the per-device sync folder (ADR 0001, decision 5).
 */
interface AutoBackupController {
    /** Why the last automatic write or read did not happen, or null while it is working. */
    val failure: StateFlow<BackupFailure?>

    fun enable(target: BackupTarget)

    fun disable()

    /** Records the user's answer when they turn the one-off offer down. */
    fun declineOffer()

    /** A manual export or import counts as a sync when it used the synced file. */
    fun noteManualBackup(target: BackupTarget, outcome: BackupOutcome)

    fun onAppForegrounded()

    fun onAppBackgrounded()
}

/**
 * The platform's file chooser, driven from Settings.
 *
 * It calls back rather than returning, because Android's is an Activity result: the answer
 * arrives after the composition that asked for it. [onPicked] is not called at all when the user
 * cancels — "no file" is not an answer worth threading through the offer flow.
 */
interface BackupFilePicker {
    /** Asks for a file to write, seeded with [suggestedName]. */
    fun pickExportTarget(suggestedName: String, onPicked: (BackupTarget) -> Unit)

    /** Asks for an existing file to read. */
    fun pickImportSource(onPicked: (BackupTarget) -> Unit)
}

/**
 * Version and database size for the About section — diagnostics the shell knows and `:core`
 * deliberately does not.
 */
data class AppInfo(
    val version: String,
    val databaseSizeBytes: Long,
)
