package de.andi1984.cadence.domain.backup

import de.andi1984.cadence.domain.id.UuidV7
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Everything the app owns, in one value: what an export writes and an import restores. */
data class BackupSnapshot(
    val projects: List<Project> = emptyList(),
    val sections: List<Section> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val settings: BackupSettings? = null,
)

/** Serializable settings for backup/import. */
@Serializable
data class BackupSettings(
    val theme: String? = null,
    val density: String? = null,
    val sortMode: String? = null,
    val showCompleted: Boolean? = null,
    val locale: String? = null,
)

/** Why a file could not be restored. The wording lives in `ui/format/BackupLabels.kt`. */
enum class BackupError { NOT_JSON, NOT_A_BACKUP, NEWER_VERSION }

sealed interface BackupReadResult {
    /**
     * [exportedAt] is what the file says about itself, or null when it carries no timestamp or
     * an unreadable one. Automatic sync orders a file against the last one this device wrote
     * with it; nothing else reads it.
     */
    data class Ok(
        val snapshot: BackupSnapshot,
        val exportedAt: Instant? = null,
    ) : BackupReadResult

    data class Failed(val reason: BackupError) : BackupReadResult
}

/**
 * The on-disk backup format. Unlike [de.andi1984.cadence.data.db.RecurrenceCodec] — which is a
 * private storage detail — this is a published contract: a future web app reads these files, so
 * dates are ISO-8601 strings and recurrence is a structured object rather than the packed
 * `v1;key=value` column.
 *
 * Forwards compatibility: unknown keys are ignored, so a newer Cadence may add fields without
 * breaking older readers. A higher [BackupDocument.version] is refused rather than guessed at.
 * That is why a purely additive field — `parentId`, say — leaves [BackupCodec.VERSION] alone:
 * bumping it would make older installs refuse the whole file over one key they can ignore.
 */
object BackupCodec {

    const val FORMAT = "cadence.backup"
    const val VERSION = 2

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: BackupSnapshot, exportedAt: Instant): String =
        json.encodeToString(
            BackupDocument.serializer(),
            BackupDocument(
                exportedAt = exportedAt.toString(),
                projects = snapshot.projects.map { it.toBackup() },
                // Sections are not in the file format yet — the codec half of the section work
                // still follows, and [BackupSnapshot.sections] is what it will write from.
                tasks = snapshot.tasks.map { it.toBackup() },
                settings = snapshot.settings,
            ),
        )

    fun decode(raw: String): BackupReadResult {
        val document = runCatching { json.decodeFromString(BackupDocument.serializer(), raw) }
            .getOrElse { return BackupReadResult.Failed(BackupError.NOT_JSON) }
        if (document.format != FORMAT) return BackupReadResult.Failed(BackupError.NOT_A_BACKUP)
        if (document.version > VERSION) return BackupReadResult.Failed(BackupError.NEWER_VERSION)

        // Rows the database could never hold are dropped rather than failing the whole restore:
        // a project needs a real id (tasks reference it) and a task needs a title. A task
        // missing its id is not dropped — unlike the old autoincrement column, nothing assigns
        // one implicitly on insert now, so decode mints a fresh one, the same substitute Room's
        // `id = 0` used to trigger.
        // Tombstones (deletedAt != null) are filtered out during import.
        val projects = document.projects.filter { it.id.isNotBlank() && it.deletedAt == null }.map { it.toDomain() }
        val knownProjects = projects.map { it.id }.toSet()
        val decoded = document.tasks.filter { it.title.isNotBlank() && it.deletedAt == null }.map { it.toDomain() }
        val knownTasks = decoded.mapTo(mutableSetOf()) { it.id }
        val tasks = decoded
            .map { task ->
                // A task pointing at a project that is not in the file lands in the Inbox
                // instead of becoming invisible.
                if (task.projectId != null && task.projectId !in knownProjects) {
                    task.copy(projectId = null)
                } else {
                    task
                }
            }
            .map { task ->
                // A recurrence link is only ever read as "does this row replace that one", so a
                // link to an occurrence the file does not contain is simply dropped.
                val replaces = task.spawnedFromId?.takeIf { it != task.id && it in knownTasks }
                if (replaces != task.spawnedFromId) task.copy(spawnedFromId = replaces) else task
            }
            .normalisedParents()
        return BackupReadResult.Ok(
            snapshot = BackupSnapshot(
                projects = projects,
                tasks = tasks,
                settings = document.settings,
            ),
            exportedAt = document.exportedAt.parseOrNull { Instant.parse(it) },
        )
    }
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

@Serializable
internal data class BackupDocument(
    val format: String = BackupCodec.FORMAT,
    val version: Int = BackupCodec.VERSION,
    val exportedAt: String? = null,
    val projects: List<BackupProject> = emptyList(),
    val tasks: List<BackupTask> = emptyList(),
    val settings: BackupSettings? = null,
)

@Serializable
internal data class BackupProject(
    val id: String = "",
    val name: String = "",
    val colorHex: String = "#006A60",
    val parentId: String? = null,
    val sortOrder: Int = 0,
    /** ISO instant, e.g. `2026-08-05T07:12:00Z`. */
    val updatedAt: String? = null,
    /** ISO instant, e.g. `2026-08-05T07:12:00Z`, or null for active records. */
    val deletedAt: String? = null,
)

@Serializable
internal data class BackupTask(
    val id: String = "",
    val title: String = "",
    val notes: String? = null,
    /** 1…4, matching [Priority.level]. */
    val priority: Int = Priority.DEFAULT.level,
    val projectId: String? = null,
    /** Id of the task this one is a subtask of, or null for a top-level task. */
    val parentId: String? = null,
    /** Id of the recurring occurrence this row replaces, or null. */
    val spawnedFromId: String? = null,
    /** ISO local date, e.g. `2026-08-05`. */
    val dueDate: String? = null,
    /** ISO local time, e.g. `09:30`. */
    val dueTime: String? = null,
    val reminderTime: String? = null,
    /** ISO instant, e.g. `2026-08-05T07:12:00Z`. */
    val completedAt: String? = null,
    val createdAt: String? = null,
    val sortOrder: Int = 0,
    val recurrence: BackupRecurrence? = null,
    /** ISO instant, e.g. `2026-08-05T07:12:00Z`. */
    val updatedAt: String? = null,
    /** ISO instant, e.g. `2026-08-05T07:12:00Z`, or null for active records. */
    val deletedAt: String? = null,
)

@Serializable
internal data class BackupRecurrence(
    val mode: String = RecurrenceMode.SCHEDULE.name,
    val interval: Int = 1,
    val unit: String = RecurrenceUnit.WEEK.name,
    /** `MONDAY`, `THURSDAY`, … as in [DayOfWeek]. */
    val daysOfWeek: List<String> = emptyList(),
    val monthlyMode: String = MonthlyMode.DAY_OF_MONTH.name,
    val dayOfMonth: Int? = null,
    val nthWeek: Int? = null,
    val nthDayOfWeek: String? = null,
    val keepMissed: Boolean = true,
)

private fun Project.toBackup() = BackupProject(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

private fun BackupProject.toDomain() = Project(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId?.takeIf { it.isNotBlank() },
    sortOrder = sortOrder,
    updatedAt = updatedAt.parseOrNull { Instant.parse(it) } ?: Instant.EPOCH,
    deletedAt = deletedAt.parseOrNull { Instant.parse(it) },
)

private fun Task.toBackup() = BackupTask(
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
    recurrence = recurrence?.toBackup(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

private fun BackupTask.toDomain() = Task(
    // Nothing assigns a fresh id implicitly on insert any more, so a missing one is minted here
    // — the direct substitute for what Room's autoincrement column used to do with `id = 0`.
    id = id.ifBlank { UuidV7.random() },
    title = title,
    notes = notes?.takeIf { it.isNotBlank() },
    priority = Priority.fromLevel(priority),
    projectId = projectId?.takeIf { it.isNotBlank() },
    parentId = parentId?.takeIf { it.isNotBlank() },
    spawnedFromId = spawnedFromId?.takeIf { it.isNotBlank() },
    dueDate = dueDate.parseOrNull { LocalDate.parse(it) },
    dueTime = dueTime.parseOrNull { LocalTime.parse(it) },
    reminderTime = reminderTime.parseOrNull { LocalTime.parse(it) },
    completedAt = completedAt.parseOrNull { Instant.parse(it) },
    createdAt = createdAt.parseOrNull { Instant.parse(it) } ?: Instant.EPOCH,
    sortOrder = sortOrder,
    recurrence = recurrence?.toDomain(),
    updatedAt = updatedAt.parseOrNull { Instant.parse(it) } ?: Instant.EPOCH,
    deletedAt = deletedAt.parseOrNull { Instant.parse(it) },
)

private fun RecurrenceRule.toBackup() = BackupRecurrence(
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

private fun BackupRecurrence.toDomain() = RecurrenceRule(
    mode = RecurrenceMode.entries.firstOrNull { it.name == mode } ?: RecurrenceMode.SCHEDULE,
    interval = interval.coerceAtLeast(1),
    unit = RecurrenceUnit.entries.firstOrNull { it.name == unit } ?: RecurrenceUnit.WEEK,
    daysOfWeek = daysOfWeek.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
        .toSet(),
    monthlyMode = MonthlyMode.entries.firstOrNull { it.name == monthlyMode }
        ?: MonthlyMode.DAY_OF_MONTH,
    dayOfMonth = dayOfMonth,
    nthWeek = nthWeek,
    nthDayOfWeek = nthDayOfWeek?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } },
    keepMissed = keepMissed,
)

/** A single unreadable date must not fail the whole restore — that field simply goes empty. */
private fun <T> String?.parseOrNull(parse: (String) -> T): T? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }
