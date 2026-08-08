package de.andi1984.cadence.data.backup

import android.content.Context
import android.net.Uri
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

/**
 * Reads and writes backup files through the Storage Access Framework: the user picks the
 * location, so the app needs no storage permission and keeps no copy of its own.
 */
class BackupIo(
    private val context: Context,
    private val repository: CadenceRepository,
    private val settingsStore: SettingsStore,
) : BackupGateway {

    override suspend fun export(target: BackupTarget): BackupOutcome = withContext(Dispatchers.IO) {
        val uri = Uri.parse(target.value)
        val snapshot = repository.snapshot()
        val exportedAt = Instant.now()
        
        // Add settings to the snapshot
        val currentSettings = settingsStore.state.value
        val backupSettings = BackupSettings(
            theme = currentSettings.theme.name,
            density = currentSettings.density.name,
            sortMode = currentSettings.sortMode.name,
            showCompleted = currentSettings.showCompleted,
            locale = null, // Locale is handled by the system, not stored in settings
        )
        
        val snapshotWithSettings = snapshot.copy(settings = backupSettings)
        val json = BackupCodec.encode(snapshotWithSettings, exportedAt)
        
        runCatching {
            // "wt" truncates: overwriting an existing backup must not leave a tail of the old one.
            context.contentResolver.openOutputStream(uri, "wt")
                ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("no output stream for $uri")
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
        when (val read = read(Uri.parse(target.value))) {
            is Read.Failed -> BackupOutcome.Failed(read.reason)
            is Read.Ok -> restore(read)
        }
    }

    /**
     * The import automatic sync performs on its own: the file only replaces what is on the
     * device when [AutoBackupPolicy] recognises it as newer than the last one this device wrote
     * or read. Returns null when there was nothing to do, so a quiet open stays quiet.
     */
    suspend fun importIfNewer(target: BackupTarget, lastSyncedAt: Instant?): BackupOutcome? =
        withContext(Dispatchers.IO) {
            when (val read = read(Uri.parse(target.value))) {
                is Read.Failed -> BackupOutcome.Failed(read.reason)
                is Read.Ok ->
                    if (AutoBackupPolicy.shouldImport(read.exportedAt, lastSyncedAt)) {
                        restore(read)
                    } else {
                        null
                    }
            }
        }

    /**
     * A file that has been read but not written to the database yet. Reading and restoring are
     * separate steps so automatic sync can decide from the file's own timestamp whether to
     * restore at all, without reading the file twice.
     */
    private sealed interface Read {
        data class Ok(val snapshot: BackupSnapshot, val exportedAt: Instant?) : Read
        data class Failed(val reason: BackupFailure) : Read
    }

    private suspend fun restore(read: Read.Ok): BackupOutcome {
        repository.restore(read.snapshot)
        
        // Restore settings if present in the backup
        read.snapshot.settings?.let { backupSettings ->
            backupSettings.theme?.let { name ->
                ThemeChoice.entries.firstOrNull { it.name == name }?.let { theme ->
                    settingsStore.setTheme(theme)
                }
            }
            backupSettings.density?.let { name ->
                Density.entries.firstOrNull { it.name == name }?.let { density ->
                    settingsStore.setDensity(density)
                }
            }
            backupSettings.sortMode?.let { name ->
                SortMode.entries.firstOrNull { it.name == name }?.let { sortMode ->
                    settingsStore.setSortMode(sortMode)
                }
            }
            backupSettings.showCompleted?.let { showCompleted ->
                settingsStore.setShowCompleted(showCompleted)
            }
        }
        
        return BackupOutcome.Imported(
            projects = read.snapshot.projects.size,
            tasks = read.snapshot.tasks.size,
            exportedAt = read.exportedAt,
        )
    }

    private fun read(uri: Uri): Read {
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readTextBounded() }
                ?: error("no input stream for $uri")
        }.getOrElse { return Read.Failed(BackupFailure.READ_FAILED) }
            ?: return Read.Failed(BackupFailure.FILE_TOO_LARGE)

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

    /**
     * A picked file is arbitrary input, so reading stops at [MAX_FILE_BYTES] (returning null)
     * rather than pulling whatever the user chose into memory. `readNBytes` would be simpler
     * but only exists from API 33.
     */
    private fun InputStream.readTextBounded(): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_FILE_BYTES) return null
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
