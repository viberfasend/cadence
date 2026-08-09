package de.andi1984.cadence.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over where the per-device sync files live (ADR 0001, decision 6).
 *
 * A synced folder contains one file per device:
 *   Cadence/
 *     cadence-sync-01917f3c-…-a4e2.json     ← this phone
 *     cadence-sync-01917f40-…-9b71.json     ← this laptop
 *
 * Each device writes only its own file and reads all of them. Write-write conflicts
 * cannot occur, so there is no lockfile, no `.sync-conflict-*` copies from the sync client,
 * and no "did someone else touch this since I last looked" check.
 *
 * Implementations:
 * - Desktop: java.nio.Path based folder watcher
 * - Android: SAF tree uri based
 * - Future: WebDAV/Nextcloud
 */
interface SyncTransport {

    /**
     * List all sync files currently present in the sync folder.
     * Each file represents one device's full current state.
     */
    suspend fun listFiles(): List<SyncFileInfo>

    /**
     * Read the contents of a specific sync file.
     */
    suspend fun readFile(fileName: String): String?

    /**
     * Write this device's state file atomically.
     * Write a temporary file in the same directory, fsync, rename over the target.
     * A sync client that copies a file mid-write otherwise propagates half of one.
     */
    suspend fun writeFile(fileName: String, content: String)

    /**
     * Delete a sync file.
     */
    suspend fun deleteFile(fileName: String)

    /**
     * Watch for changes in the sync folder and emit when new files appear or existing ones change.
     * Desktop additionally watches the folder — a desktop app stays open for days, and a start-only
     * read would never see the phone's changes.
     */
    fun watchFiles(): Flow<Unit>
}

/**
 * Information about a sync file.
 */
data class SyncFileInfo(
    val fileName: String,
    val lastModified: Long,
    val sizeBytes: Long,
)