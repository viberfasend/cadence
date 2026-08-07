package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.andi1984.cadence.R
import de.andi1984.cadence.data.backup.BackupFailure
import de.andi1984.cadence.data.backup.BackupOutcome

/** Turns the result of an export or import into the sentence shown under the buttons. */
@Composable
fun backupOutcomeText(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Exported ->
        stringResource(R.string.backup_exported, taskCount(outcome.tasks), projectCount(outcome.projects))

    is BackupOutcome.Imported ->
        stringResource(R.string.backup_imported, taskCount(outcome.tasks), projectCount(outcome.projects))

    is BackupOutcome.Failed -> backupFailureText(outcome.reason)
}

/** The same sentences on their own, for the failure automatic sync reports in Settings. */
@Composable
fun backupFailureText(reason: BackupFailure): String = stringResource(
    when (reason) {
        BackupFailure.WRITE_FAILED -> R.string.backup_error_write
        BackupFailure.READ_FAILED -> R.string.backup_error_read
        BackupFailure.FILE_TOO_LARGE -> R.string.backup_error_too_large
        BackupFailure.NOT_JSON -> R.string.backup_error_not_json
        BackupFailure.NOT_A_BACKUP -> R.string.backup_error_not_a_backup
        BackupFailure.NEWER_VERSION -> R.string.backup_error_newer_version
    },
)

@Composable
private fun taskCount(count: Int): String =
    pluralStringResource(R.plurals.task_count, count, count)

@Composable
private fun projectCount(count: Int): String =
    pluralStringResource(R.plurals.project_count, count, count)
