package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome

/** Turns the result of an export or import into the sentence shown under the buttons. */
@Composable
fun backupOutcomeText(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Exported ->
        stringResource(Res.string.backup_exported, taskCount(outcome.tasks), projectCount(outcome.projects))

    is BackupOutcome.Imported ->
        stringResource(Res.string.backup_imported, taskCount(outcome.tasks), projectCount(outcome.projects))

    is BackupOutcome.Failed -> backupFailureText(outcome.reason)
}

/** The same sentences on their own, for the failure automatic sync reports in Settings. */
@Composable
fun backupFailureText(reason: BackupFailure): String = stringResource(
    when (reason) {
        BackupFailure.WRITE_FAILED -> Res.string.backup_error_write
        BackupFailure.READ_FAILED -> Res.string.backup_error_read
        BackupFailure.FILE_TOO_LARGE -> Res.string.backup_error_too_large
        BackupFailure.NOT_JSON -> Res.string.backup_error_not_json
        BackupFailure.NOT_A_BACKUP -> Res.string.backup_error_not_a_backup
        BackupFailure.NEWER_VERSION -> Res.string.backup_error_newer_version
    },
)

@Composable
private fun taskCount(count: Int): String =
    pluralStringResource(Res.plurals.task_count, count, count)

@Composable
private fun projectCount(count: Int): String =
    pluralStringResource(Res.plurals.project_count, count, count)
