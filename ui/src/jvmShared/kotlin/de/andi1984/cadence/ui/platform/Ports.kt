package de.andi1984.cadence.ui.platform

import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Task

/**
 * What the ViewModel needs from the machine it is running on.
 *
 * `:core` already states storage as a port rather than a layer; these are the same idea for
 * everything else the app touches that Android and the desktop do differently — alarms, the file
 * a backup is written to, the dialog that picks it. `:ui` declares them, the shells implement
 * them (`:app-android` with AlarmManager and the Storage Access Framework, `:app-desktop` with a
 * poll, a tray icon and `java.nio`), and no screen learns which it got.
 *
 * Sync is deliberately *not* here: HTTPS and JSON are the same on both platforms, so
 * `CadenceSyncEngine` is a plain class in `:core` (ADR 0002, decision 7).
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
