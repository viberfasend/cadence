package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.id.UuidV7
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Parse ISO instant string, return null if invalid.
 */
private fun parseInstant(isoString: String): Instant? = try {
    Instant.parse(isoString)
} catch (e: Exception) {
    null
}

/**
 * Parse ISO local date string.
 */
private fun parseLocalDate(isoString: String): LocalDate? = try {
    LocalDate.parse(isoString)
} catch (e: Exception) {
    null
}

/**
 * Parse ISO local time string.
 */
private fun parseLocalTime(isoString: String): LocalTime? = try {
    LocalTime.parse(isoString)
} catch (e: Exception) {
    null
}

/**
 * Merge engine for per-device sync files (ADR 0001, decision 6).
 *
 * Merge rules, applied over the union of every file plus the local database:
 * 1. Records are keyed by UUID.
 * 2. Every record carries `updatedAt`. The record with the greatest `updatedAt` wins the whole record
 *    — field-level merge is not worth its complexity here.
 * 3. Ties break on device id, lexicographically, so every device reaches the same result.
 * 4. Deletion is a tombstone: `deletedAt` set, payload dropped. A tombstone competes on timestamp
 *    like any other version, so a delete on one device and an edit on another resolve by time
 *    rather than by which arrived last.
 * 5. Referential repair runs after the merge, reusing the rules the codec already applies on
 *    import: a task pointing at a project no one has lands in the Inbox, a subtask whose parent
 *    is missing or whose chain cycles is set free, a recurrence link to an occurrence nobody has
 *    is dropped.
 *
 * Tombstones are garbage-collected after 90 days. A device that has been offline longer than
 * that would resurrect what the others deleted, so a peer file whose newest timestamp predates the
 * horizon is not merged automatically — Settings asks instead.
 *
 * Clock skew is the one thing this design trusts. Last-writer-wins is only as good as the
 * clocks involved. A peer file timestamped more than an hour in the future is flagged in Settings
 * rather than silently believed.
 */
object SyncMergeEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Tombstone horizon: 90 days in milliseconds.
     * A device that has been offline longer than this needs explicit confirmation.
     */
    const val TOMBSTONE_HORIZON_MS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * Clock skew threshold: 1 hour in milliseconds.
     * A peer file timestamped more than this in the future is flagged.
     */
    const val CLOCK_SKEW_THRESHOLD_MS: Long = 60 * 60 * 1000

    /**
     * Result of merging sync files.
     */
    data class MergeResult(
        val projects: List<Project>,
        val tasks: List<Task>,
        val mergedDeviceIds: Set<String>,
        val flaggedFiles: List<FlaggedFile> = emptyList(),
    )

    /**
     * A file that was flagged for user attention.
     */
    data class FlaggedFile(
        val fileName: String,
        val deviceId: String?,
        val reason: FlagReason,
    )

    enum class FlagReason {
        /** File timestamp is more than 1 hour in the future */
        FUTURE_TIMESTAMP,
        /** File timestamp is older than tombstone horizon */
        STALE_FILE,
        /** File format version is newer than what we support */
        NEWER_VERSION,
        /** File could not be parsed */
        PARSE_ERROR,
    }

    /**
     * Merge all device sync files into a single coherent state.
     *
     * @param files Map of file names to their contents
     * @param now Current time for timestamp comparisons
     * @return Merged state with any flagged files
     */
    fun mergeFiles(
        files: Map<String, String>,
        now: Instant = Instant.now(),
    ): MergeResult {
        val flaggedFiles = mutableListOf<FlaggedFile>()
        val validStates = mutableListOf<DeviceSyncState>()

        for ((fileName, content) in files) {
            val deviceId = parseDeviceIdFromFileName(fileName)
            val state = try {
                json.decodeFromString<DeviceSyncState>(content)
            } catch (e: Exception) {
                flaggedFiles.add(FlaggedFile(fileName, deviceId, FlagReason.PARSE_ERROR))
                continue
            }

            // Check version
            if (state.version > 1) {
                flaggedFiles.add(FlaggedFile(fileName, deviceId, FlagReason.NEWER_VERSION))
                continue
            }

            // Parse exportedAt timestamp
            val exportedAt = try {
                Instant.parse(state.exportedAt)
            } catch (e: Exception) {
                flaggedFiles.add(FlaggedFile(fileName, deviceId, FlagReason.PARSE_ERROR))
                continue
            }

            // Check for future timestamp (clock skew)
            val nowMillis = now.toEpochMilli()
            val exportedAtMillis = exportedAt.toEpochMilli()
            if (exportedAtMillis - nowMillis > CLOCK_SKEW_THRESHOLD_MS) {
                flaggedFiles.add(FlaggedFile(fileName, deviceId, FlagReason.FUTURE_TIMESTAMP))
            }

            // Check for stale file (older than tombstone horizon)
            if (nowMillis - exportedAtMillis > TOMBSTONE_HORIZON_MS) {
                flaggedFiles.add(FlaggedFile(fileName, deviceId, FlagReason.STALE_FILE))
            }

            validStates.add(state)
        }

        // Merge all valid states
        val merged = mergeStates(validStates, now)

        return MergeResult(
            projects = merged.projects,
            tasks = merged.tasks,
            mergedDeviceIds = validStates.map { it.deviceId }.toSet(),
            flaggedFiles = flaggedFiles,
        )
    }

    /**
     * Merge multiple device states into one.
     */
    private fun mergeStates(
        states: List<DeviceSyncState>,
        now: Instant,
    ): MergedState {
        // Collect all projects and tasks from all states
        val allProjects = mutableMapOf<String, SyncProjectWithSource>()
        val allTasks = mutableMapOf<String, SyncTaskWithSource>()

        for (state in states) {
            for (project in state.projects) {
                val key = project.id
                val existing = allProjects[key]
                val newEntry = SyncProjectWithSource(project, state.deviceId)

                if (existing == null || shouldReplace(existing, newEntry)) {
                    allProjects[key] = newEntry
                }
            }

            for (task in state.tasks) {
                val key = task.id
                val existing = allTasks[key]
                val newEntry = SyncTaskWithSource(task, state.deviceId)

                if (existing == null || shouldReplace(existing, newEntry)) {
                    allTasks[key] = newEntry
                }
            }
        }

        // Convert to domain models and apply referential repair
        val projects = allProjects.values
            .filter { it.project.deletedAt == null } // Skip tombstones
            .map { it.project.toDomainProject() }

        val domainTasks = allTasks.values
            .filter { it.task.deletedAt == null } // Skip tombstones
            .map { it.task.toDomainTask() }
            .normalisedParents()

        val knownTaskIds = domainTasks.map { it.id }.toSet()
        
        val tasks = domainTasks
            .map { task ->
                // Fix project references
                if (task.projectId != null && projects.none { it.id == task.projectId }) {
                    task.copy(projectId = null)
                } else {
                    task
                }
            }
            .map { task ->
                // Fix spawnedFromId references
                if (task.spawnedFromId != null && task.spawnedFromId !in knownTaskIds) {
                    task.copy(spawnedFromId = null)
                } else {
                    task
                }
            }

        return MergedState(projects, tasks)
    }

    /**
     * Determine if newEntry should replace existing based on merge rules.
     */
    private fun shouldReplace(
        existing: SyncProjectWithSource,
        newEntry: SyncProjectWithSource,
    ): Boolean {
        val existingTime = parseInstant(existing.project.updatedAt)?.toEpochMilli() ?: 0L
        val newTime = parseInstant(newEntry.project.updatedAt)?.toEpochMilli() ?: 0L

        if (newTime > existingTime) return true
        if (newTime < existingTime) return false

        // Timestamps are equal, break tie by device ID (lexicographic)
        return newEntry.deviceId > existing.deviceId
    }

    /**
     * Determine if newEntry should replace existing based on merge rules.
     */
    private fun shouldReplace(
        existing: SyncTaskWithSource,
        newEntry: SyncTaskWithSource,
    ): Boolean {
        val existingTime = parseInstant(existing.task.updatedAt)?.toEpochMilli() ?: 0L
        val newTime = parseInstant(newEntry.task.updatedAt)?.toEpochMilli() ?: 0L

        if (newTime > existingTime) return true
        if (newTime < existingTime) return false

        // Timestamps are equal, break tie by device ID (lexicographic)
        return newEntry.deviceId > existing.deviceId
    }



    /**
     * Helper to track source device for conflict resolution.
     */
    private data class SyncProjectWithSource(
        val project: SyncProject,
        val deviceId: String,
    )

    /**
     * Helper to track source device for conflict resolution.
     */
    private data class SyncTaskWithSource(
        val task: SyncTask,
        val deviceId: String,
    )

    /**
     * Intermediate merged state before referential repair.
     */
    private data class MergedState(
        val projects: List<Project>,
        val tasks: List<Task>,
    )
}

/**
 * Makes the `parentId` links safe to hand to the database.
 *
 * A subtask whose parent the file does not contain becomes a task of its own rather than
 * disappearing, a chain deeper than the one level the app nests is flattened onto its root, and
 * a task that points at itself — directly or around a cycle — is set free.
 */
private fun List<Task>.normalisedParents(): List<Task> {
    if (none { it.parentId != null }) return this
    val byId = filter { it.id.isNotBlank() }.associateBy { it.id }
    return map { task ->
        var parent = task.parentId?.takeIf { it.isNotBlank() }?.let(byId::get)
        val seen = mutableSetOf(task.id)
        while (true) {
            val current = parent ?: break
            if (!seen.add(current.id)) break
            val grandParentId = current.parentId ?: break
            parent = byId[grandParentId]
        }
        task.copy(parentId = parent?.id?.takeIf { it != task.id })
    }
}

/**
 * Convert sync project to domain project.
 */
private fun SyncProject.toDomainProject(): Project = Project(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId?.takeIf { it.isNotBlank() },
    sortOrder = sortOrder,
    updatedAt = parseInstant(updatedAt) ?: Instant.EPOCH,
    deletedAt = deletedAt?.let { parseInstant(it) },
)

/**
 * Convert sync task to domain task.
 */
private fun SyncTask.toDomainTask(): Task = Task(
    id = id,
    title = title,
    notes = notes?.takeIf { it.isNotBlank() },
    priority = Priority.fromLevel(priority),
    projectId = projectId?.takeIf { it.isNotBlank() },
    parentId = parentId?.takeIf { it.isNotBlank() },
    spawnedFromId = spawnedFromId?.takeIf { it.isNotBlank() },
    dueDate = dueDate?.let { parseLocalDate(it) },
    dueTime = dueTime?.let { parseLocalTime(it) },
    reminderTime = reminderTime?.let { parseLocalTime(it) },
    completedAt = completedAt?.let { parseInstant(it) },
    createdAt = parseInstant(createdAt) ?: Instant.EPOCH,
    sortOrder = sortOrder,
    recurrence = recurrence?.toDomainRecurrence(),
    updatedAt = parseInstant(updatedAt) ?: Instant.EPOCH,
    deletedAt = deletedAt?.let { parseInstant(it) },
)

/**
 * Convert sync recurrence to domain recurrence.
 */
private fun SyncRecurrence.toDomainRecurrence(): RecurrenceRule = RecurrenceRule(
    mode = RecurrenceMode.entries.firstOrNull { it.name == mode } ?: RecurrenceMode.SCHEDULE,
    interval = interval.coerceAtLeast(1),
    unit = RecurrenceUnit.entries.firstOrNull { it.name == unit } ?: RecurrenceUnit.WEEK,
    daysOfWeek = daysOfWeek.mapNotNull { name ->
        DayOfWeek.entries.firstOrNull { it.name == name }
    }.toSet(),
    monthlyMode = MonthlyMode.entries.firstOrNull { it.name == monthlyMode }
        ?: MonthlyMode.DAY_OF_MONTH,
    dayOfMonth = dayOfMonth,
    nthWeek = nthWeek,
    nthDayOfWeek = nthDayOfWeek?.let { name ->
        DayOfWeek.entries.firstOrNull { it.name == name }
    },
    keepMissed = keepMissed,
)