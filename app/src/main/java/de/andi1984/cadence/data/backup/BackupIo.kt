package de.andi1984.cadence.data.backup

import android.content.Context
import android.net.Uri
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.backup.AutoBackupPolicy
import de.andi1984.cadence.domain.backup.BackupCodec
import de.andi1984.cadence.domain.backup.BackupError
import de.andi1984.cadence.domain.backup.BackupReadResult
import de.andi1984.cadence.domain.backup.BackupSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

enum class BackupFailure { WRITE_FAILED, READ_FAILED, FILE_TOO_LARGE, NOT_JSON, NOT_A_BACKUP, NEWER_VERSION }

/**
 * What the last export or import did, for the line the Settings screen shows afterwards.
 *
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

    data class Imported(
        val projects: Int,
        val tasks: Int,
        val exportedAt: Instant? = null,
    ) : BackupOutcome

    data class Failed(val reason: BackupFailure) : BackupOutcome
}

/**
 * Reads and writes backup files through the Storage Access Framework: the user picks the
 * location, so the app needs no storage permission and keeps no copy of its own.
 */
class BackupIo(
    private val context: Context,
    private val repository: CadenceRepository,
) {

    suspend fun export(uri: Uri): BackupOutcome = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        val exportedAt = Instant.now()
        val json = BackupCodec.encode(snapshot, exportedAt)
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

    suspend fun import(uri: Uri): BackupOutcome = withContext(Dispatchers.IO) {
        when (val read = read(uri)) {
            is Read.Failed -> BackupOutcome.Failed(read.reason)
            is Read.Ok -> restore(read)
        }
    }

    /**
     * The import automatic sync performs on its own: the file only replaces what is on the
     * device when [AutoBackupPolicy] recognises it as newer than the last one this device wrote
     * or read. Returns null when there was nothing to do, so a quiet open stays quiet.
     */
    suspend fun importIfNewer(uri: Uri, lastSyncedAt: Instant?): BackupOutcome? =
        withContext(Dispatchers.IO) {
            when (val read = read(uri)) {
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
