package de.andi1984.cadence.domain.backup

import java.time.Instant

/**
 * What an export or import did, and why it did not.
 *
 * These live in `:core` rather than next to the I/O that produces them because both sides of the
 * boundary need them: the platform shell writes the file (SAF on Android, `java.nio` on the
 * desktop) and `:ui` turns the result into the sentence Settings shows.
 */
enum class BackupFailure { WRITE_FAILED, READ_FAILED, FILE_TOO_LARGE, NOT_JSON, NOT_A_BACKUP, NEWER_VERSION }

/**
 * [BackupOutcome.Exported.exportedAt] and [BackupOutcome.Imported.exportedAt] are the file's own
 * timestamp: automatic sync records it so it can tell a file it wrote itself from one that
 * arrived from elsewhere. Nothing in the UI reads them.
 */
sealed interface BackupOutcome {
    data class Exported(
        val projects: Int,
        val tasks: Int,
        val exportedAt: Instant,
    ) : BackupOutcome

    /**
     * [files] and [unreadableFiles] describe an import of several files at once — Settings
     * hands the gateway one file at a time and adds the results up, so a folder of them reports
     * as one sentence. They default to "one file, and it worked", which is what a single import
     * is, so nothing that produces one outcome has to think about them.
     */
    data class Imported(
        val projects: Int,
        val tasks: Int,
        val exportedAt: Instant? = null,
        val files: Int = 1,
        val unreadableFiles: Int = 0,
    ) : BackupOutcome

    data class Failed(val reason: BackupFailure) : BackupOutcome
}
