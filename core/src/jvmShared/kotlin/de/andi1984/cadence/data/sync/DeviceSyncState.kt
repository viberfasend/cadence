package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * A device's full current state for sync purposes (ADR 0001, decision 6).
 *
 * Each device writes only its own file and reads all of them. The file contains:
 * - Device metadata (id, label)
 * - Full state: projects and tasks
 * - Timestamp for conflict resolution
 *
 * The merge engine resolves conflicts by:
 * 1. Records are keyed by UUID
 * 2. Every record carries `updatedAt`. The record with the greatest `updatedAt` wins the whole record
 * 3. Ties break on device id, lexicographically, so every device reaches the same result
 * 4. Deletion is a tombstone: `deletedAt` set, payload dropped
 */
@Serializable
data class DeviceSyncState(
    /** Device identifier - UUIDv7 */
    val deviceId: String,
    /** Human-readable device label (e.g., "Andi's Pixel") */
    val deviceLabel: String,
    /** When this state was exported */
    val exportedAt: String, // ISO instant
    /** Sync format version */
    val version: Int = 1,
    /** All projects this device knows about */
    val projects: List<SyncProject> = emptyList(),
    /** All tasks this device knows about */
    val tasks: List<SyncTask> = emptyList(),
)

@Serializable
data class SyncProject(
    val id: String,
    val name: String,
    val colorHex: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    /** Last write timestamp for conflict resolution */
    val updatedAt: String, // ISO instant
    /** Tombstone: set instead of hard delete */
    val deletedAt: String? = null,
)

@Serializable
data class SyncTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val priority: Int = 3, // 1-4, matching Priority.level
    val projectId: String? = null,
    val parentId: String? = null,
    val spawnedFromId: String? = null,
    val dueDate: String? = null, // ISO local date
    val dueTime: String? = null, // ISO local time
    val reminderTime: String? = null, // ISO local time
    val completedAt: String? = null, // ISO instant
    val createdAt: String, // ISO instant
    val sortOrder: Int = 0,
    val recurrence: SyncRecurrence? = null,
    /** Last write timestamp for conflict resolution */
    val updatedAt: String, // ISO instant
    /** Tombstone: set instead of hard delete */
    val deletedAt: String? = null,
)

@Serializable
data class SyncRecurrence(
    val mode: String, // RecurrenceMode name
    val interval: Int = 1,
    val unit: String, // RecurrenceUnit name
    val daysOfWeek: List<String> = emptyList(),
    val monthlyMode: String = "DAY_OF_MONTH", // MonthlyMode name
    val dayOfMonth: Int? = null,
    val nthWeek: Int? = null,
    val nthDayOfWeek: String? = null,
    val keepMissed: Boolean = true,
)

/**
 * File naming convention for device sync files.
 * Format: cadence-sync-{deviceId}.json
 */
fun deviceSyncFileName(deviceId: String): String = "cadence-sync-$deviceId.json"

/**
 * Extract device ID from a sync file name.
 * Returns null if the file name doesn't match the expected pattern.
 */
fun parseDeviceIdFromFileName(fileName: String): String? {
    val prefix = "cadence-sync-"
    val suffix = ".json"
    return if (fileName.startsWith(prefix) && fileName.endsWith(suffix)) {
        fileName.removePrefix(prefix).removeSuffix(suffix).takeIf { it.length == 36 }
    } else {
        null
    }
}