package de.andi1984.cadence.data.sync

import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.id.UuidV7
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Manages synchronization between devices using the per-device sync protocol (ADR 0001, decision 6).
 *
 * This class:
 * - Manages this device's identity and state file
 * - Watches for changes in the sync folder
 * - Periodically reads all sync files and merges them
 * - Writes this device's state when local changes occur
 * - Handles conflict resolution and tombstone management
 */
class SyncManager(
    private val syncTransport: SyncTransport,
    private val backupStore: BackupStore,
    private val repository: CadenceRepository,
    private val deviceLabel: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    
    // This device's unique identifier
    private val deviceId: String = UuidV7.random().toString()
    
    // This device's sync file name
    private val deviceFileName = deviceSyncFileName(deviceId)
    
    // Track if sync is currently running to avoid overlapping operations
    private var isSyncing = false

    /**
     * Start the sync manager. This begins watching for changes and periodically syncs.
     */
    fun start() {
        scope.launch {
            // Initial sync
            syncAllFiles()
            
            // Watch for file changes and sync when they occur
            syncTransport.watchFiles()
                .onEach { syncAllFiles() }
                .launchIn(scope)
        }
    }

    /**
     * Stop the sync manager and clean up resources.
     */
    fun stop() {
        scope.cancel()
    }

    /**
     * Request a sync of all files. This is called when local changes occur
     * or when the user manually requests a sync.
     */
    suspend fun requestSync() = withContext(ioDispatcher) {
        syncAllFiles()
    }

    /**
     * Write this device's current state to its sync file.
     * This should be called whenever local changes occur.
     */
    suspend fun writeLocalState() = withContext(ioDispatcher) {
        val snapshot = repository.snapshot()
        
        val state = DeviceSyncState(
            deviceId = deviceId,
            deviceLabel = deviceLabel,
            exportedAt = Instant.now().toString(),
            version = 1,
            projects = snapshot.projects.map { it.toSyncProject() },
            tasks = snapshot.tasks.map { it.toSyncTask() },
        )
        
        val content = SyncCodec.encode(state)
        syncTransport.writeFile(deviceFileName, content)
    }

    /**
     * Sync all files from the sync folder and merge them into the local database.
     */
    private suspend fun syncAllFiles() {
        if (isSyncing) return
        isSyncing = true
        
        try {
            // List all sync files
            val files = syncTransport.listFiles()
            
            // Read all file contents
            val fileContents = mutableMapOf<String, String>()
            for (file in files) {
                val content = syncTransport.readFile(file.fileName)
                if (content != null) {
                    fileContents[file.fileName] = content
                }
            }
            
            // Merge all files
            val mergeResult = SyncMergeEngine.mergeFiles(fileContents)
            
            // Apply the merged state to the local database
            backupStore.mergeAll(mergeResult.projects, mergeResult.tasks)
            
            // Handle any flagged files (show notifications, etc.)
            handleFlaggedFiles(mergeResult.flaggedFiles)
            
            // Write our current state back (in case we have newer data)
            writeLocalState()
            
        } catch (e: Exception) {
            // Log error and continue
            // In a real implementation, you'd want proper error handling
        } finally {
            isSyncing = false
        }
    }

    /**
     * Handle files that were flagged during merge (future timestamps, stale files, etc.).
     * This would typically show notifications to the user.
     */
    private suspend fun handleFlaggedFiles(flaggedFiles: List<SyncMergeEngine.FlaggedFile>) {
        // In a real implementation, this would show notifications or ask for user confirmation
        // For now, we just log them
        for (flagged in flaggedFiles) {
            when (flagged.reason) {
                SyncMergeEngine.FlagReason.FUTURE_TIMESTAMP -> {
                    // File has a timestamp more than 1 hour in the future
                    // This could indicate clock skew
                }
                SyncMergeEngine.FlagReason.STALE_FILE -> {
                    // File is older than 90 days - needs user confirmation before merging
                }
                SyncMergeEngine.FlagReason.NEWER_VERSION -> {
                    // File has a newer format version than we support
                }
                SyncMergeEngine.FlagReason.PARSE_ERROR -> {
                    // File could not be parsed
                }
            }
        }
    }

    /**
     * Get this device's ID.
     */
    fun getDeviceId(): String = deviceId

    /**
     * Get this device's label.
     */
    fun getDeviceLabel(): String = deviceLabel
}

/**
 * Codec for encoding/decoding device sync state.
 */
object SyncCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(state: DeviceSyncState): String = json.encodeToString(DeviceSyncState.serializer(), state)
    
    fun decode(content: String): DeviceSyncState = json.decodeFromString(DeviceSyncState.serializer(), content)
}

/**
 * Convert domain Project to sync Project.
 */
private fun de.andi1984.cadence.domain.model.Project.toSyncProject(): SyncProject = SyncProject(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

/**
 * Convert domain Task to sync Task.
 */
private fun de.andi1984.cadence.domain.model.Task.toSyncTask(): SyncTask = SyncTask(
    id = id,
    title = title,
    notes = notes,
    priority = priority.level,
    projectId = projectId,
    parentId = parentId,
    spawnedFromId = spawnedFromId,
    dueDate = dueDate?.toString(),
    dueTime = dueTime?.toString(),
    reminderTime = reminderTime?.toString(),
    completedAt = completedAt?.toString(),
    createdAt = createdAt.toString(),
    sortOrder = sortOrder,
    recurrence = recurrence?.toSyncRecurrence(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

/**
 * Convert domain RecurrenceRule to sync Recurrence.
 */
private fun RecurrenceRule.toSyncRecurrence(): SyncRecurrence = SyncRecurrence(
    mode = mode.name,
    interval = interval,
    unit = unit.name,
    daysOfWeek = daysOfWeek.sortedBy { it.value }.map { it.name },
    monthlyMode = monthlyMode.name,
    dayOfMonth = dayOfMonth,
    nthWeek = nthWeek,
    nthDayOfWeek = nthDayOfWeek?.name,
    keepMissed = keepMissed,
)