package de.andi1984.cadence.ui.platform

import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Task
import java.io.File
import java.io.InputStream

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
    /**
     * Every task in [tasks] gets one alarm per candidate lead — every positive entry in
     * [leadMinutes], that many minutes before [Task.dueTime], plus the moment [Task.reminderTime]
     * itself names — each either (re)scheduled or cancelled, so removing a reminder or changing
     * [leadMinutes] in Settings takes effect on the next sync.
     */
    fun sync(tasks: List<Task>, leadMinutes: List<Int>)

    /** Drops a task's reminders outright, every lead included — for a row that is about to stop
     *  existing, which [sync] would never see again. */
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

    /**
     * Asks for existing files to read — one, or as many as the user cares to select.
     *
     * Several at once because an import is not always one file: the Todoist converter
     * (`tools/todoist_import.py --split`) writes one per project, and picking them one at a
     * time would mean walking the same confirm dialog twenty times. [onPicked] is never called
     * with an empty list — that is a cancelled picker, not an answer.
     */
    fun pickImportSource(onPicked: (List<BackupTarget>) -> Unit)
}

/**
 * Version and database size for the About section — diagnostics the shell knows and `:core`
 * deliberately does not.
 */
data class AppInfo(
    val version: String,
    val databaseSizeBytes: Long,
)

/**
 * A file the user just chose, with its bytes still unread.
 *
 * [open] rather than a `ByteArray`: an attachment is up to 25 MB, and the repository streams what
 * it is given straight into the blob store while hashing it, so nothing ever needs the whole file
 * in memory. It is called exactly once, off the main thread, and the caller closes the stream.
 *
 * On Android the grant behind that stream lives only as long as the Activity that asked for it,
 * which is why the read happens immediately rather than being deferred until a screen wants it.
 */
class PickedFile(
    val name: String,
    val mimeType: String,
    val open: () -> InputStream,
)

/**
 * The platform's file chooser for attachments — SAF's `OpenDocument` on Android, Swing's chooser
 * on the desktop.
 *
 * Separate from [BackupFilePicker] rather than a method on it: that one speaks [BackupTarget], an
 * opaque handle `:ui` only ever hands back, and this one has to yield *bytes* plus the name and
 * media type the row is drawn from. Callback-shaped for the same reason as its neighbour —
 * Android's answer arrives after the composition that asked for it.
 */
interface AttachmentFilePicker {
    /** [onPicked] is not called when the user cancels — "no file" is not an answer. */
    fun pickFile(onPicked: (PickedFile) -> Unit)
}

/**
 * Opens an attachment with whatever the machine has for it.
 *
 * Both methods answer whether anything took it, so a screen can say "no app can open this" rather
 * than look broken: a phone with no PDF viewer and a headless desktop are both ordinary states,
 * not errors.
 *
 * [openFile] takes the blob straight from `CadenceRepository.blobFile`. Android wraps it in its
 * `FileProvider` first — a file under `filesDir` is unreadable to every other app — and the
 * desktop hands it to `java.awt.Desktop`.
 */
interface AttachmentOpener {
    fun openFile(file: File, name: String, mimeType: String): Boolean

    fun openLink(url: String): Boolean
}
