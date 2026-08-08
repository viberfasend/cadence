package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.backup.AutoBackupPolicy
import de.andi1984.cadence.domain.backup.BackupCodec
import de.andi1984.cadence.domain.backup.BackupError
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.backup.BackupReadResult
import de.andi1984.cadence.domain.backup.BackupSettings
import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Reads and writes backup files on plain `java.nio` paths — the desktop has no Storage Access
 * Framework, and [BackupTarget.value] is an absolute path here rather than a content uri. The
 * rest of the shape mirrors `:app-android`'s `BackupIo` deliberately: same failure mapping, same
 * settings round-trip, same bounded read.
 */
class DesktopBackupIo(
    private val repository: CadenceRepository,
    private val settingsStore: SettingsStore,
) : BackupGateway {

    override suspend fun export(target: BackupTarget): BackupOutcome = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        val exportedAt = Instant.now()

        val currentSettings = settingsStore.state.value
        val snapshotWithSettings = snapshot.copy(
            settings = BackupSettings(
                theme = currentSettings.theme.name,
                density = currentSettings.density.name,
                sortMode = currentSettings.sortMode.name,
                showCompleted = currentSettings.showCompleted,
                locale = null,
            ),
        )
        val json = BackupCodec.encode(snapshotWithSettings, exportedAt)

        runCatching {
            val file = File(target.value)
            file.parentFile?.mkdirs()
            // A temp file + atomic rename, not a direct write: a crash mid-write must not leave
            // a half-written file where a good backup used to be.
            val tmp = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }.fold(
            onSuccess = {
                BackupOutcome.Exported(
                    projects = snapshot.projects.size,
                    tasks = snapshot.tasks.size,
                    exportedAt = exportedAt,
                )
            },
            onFailure = { BackupOutcome.Failed(BackupFailure.WRITE_FAILED) },
        )
    }

    override suspend fun import(target: BackupTarget): BackupOutcome = withContext(Dispatchers.IO) {
        when (val read = read(target)) {
            is Read.Failed -> BackupOutcome.Failed(read.reason)
            is Read.Ok -> restore(read)
        }
    }

    /** Same "only if newer" rule automatic sync uses on Android — see
     *  [de.andi1984.cadence.domain.backup.AutoBackupPolicy]. */
    suspend fun importIfNewer(target: BackupTarget, lastSyncedAt: Instant?): BackupOutcome? =
        withContext(Dispatchers.IO) {
            when (val read = read(target)) {
                is Read.Failed -> BackupOutcome.Failed(read.reason)
                is Read.Ok ->
                    if (AutoBackupPolicy.shouldImport(read.exportedAt, lastSyncedAt)) {
                        restore(read)
                    } else {
                        null
                    }
            }
        }

    private sealed interface Read {
        data class Ok(val snapshot: BackupSnapshot, val exportedAt: Instant?) : Read
        data class Failed(val reason: BackupFailure) : Read
    }

    private suspend fun restore(read: Read.Ok): BackupOutcome {
        repository.restore(read.snapshot)

        read.snapshot.settings?.let { backupSettings ->
            backupSettings.theme?.let { name ->
                ThemeChoice.entries.firstOrNull { it.name == name }?.let(settingsStore::setTheme)
            }
            backupSettings.density?.let { name ->
                Density.entries.firstOrNull { it.name == name }?.let(settingsStore::setDensity)
            }
            backupSettings.sortMode?.let { name ->
                SortMode.entries.firstOrNull { it.name == name }?.let(settingsStore::setSortMode)
            }
            backupSettings.showCompleted?.let(settingsStore::setShowCompleted)
        }

        return BackupOutcome.Imported(
            projects = read.snapshot.projects.size,
            tasks = read.snapshot.tasks.size,
            exportedAt = read.exportedAt,
        )
    }

    private fun read(target: BackupTarget): Read {
        val file = File(target.value)
        if (!file.exists() || file.length() > MAX_FILE_BYTES) {
            return Read.Failed(if (file.exists()) BackupFailure.FILE_TOO_LARGE else BackupFailure.READ_FAILED)
        }
        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return Read.Failed(BackupFailure.READ_FAILED) }

        return when (val result = BackupCodec.decode(raw)) {
            is BackupReadResult.Failed -> Read.Failed(
                when (result.reason) {
                    BackupError.NOT_JSON -> BackupFailure.NOT_JSON
                    BackupError.NOT_A_BACKUP -> BackupFailure.NOT_A_BACKUP
                    BackupError.NEWER_VERSION -> BackupFailure.NEWER_VERSION
                },
            )

            is BackupReadResult.Ok -> Read.Ok(result.snapshot, result.exportedAt)
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
