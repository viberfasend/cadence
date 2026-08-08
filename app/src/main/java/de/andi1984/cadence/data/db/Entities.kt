package de.andi1984.cadence.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["parentId"], name = "idx_projects_parent"),
        Index(value = ["sortOrder"], name = "idx_projects_sort"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorHex: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["projectId"], name = "idx_tasks_project"),
        Index(value = ["parentId"], name = "idx_tasks_parent"),
        Index(value = ["spawnedFromId"], name = "idx_tasks_spawned_from"),
        Index(value = ["dueDate"], name = "idx_tasks_due"),
        Index(value = ["completedAt"], name = "idx_tasks_completed"),
        Index(value = ["sortOrder"], name = "idx_tasks_sort"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val notes: String? = null,
    val priority: Int = Priority.DEFAULT.level,
    val projectId: Long? = null,
    /** Id of the task this one is a step of, or null for a top-level task. */
    val parentId: Long? = null,
    /**
     * Id of the occurrence whose completion inserted this row.
     *
     * Deliberately not a foreign key: the row it names may be deleted long before this one, and
     * a link that has gone stale is harmless — ids are never reused (`AUTOINCREMENT`), so it can
     * only ever fail to match.
     */
    val spawnedFromId: Long? = null,
    /** Epoch day, or null for "no due date". */
    val dueDate: Long? = null,
    /** Second of day, or null when the task is due on a day but not at a time. */
    val dueTime: Int? = null,
    val reminderTime: Int? = null,
    /** Epoch millis of completion, or null while open. */
    val completedAt: Long? = null,
    val createdAt: Long = 0L,
    val sortOrder: Int = 0,
    val recurrence: String? = null,
)

fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder,
)

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    notes = notes,
    priority = Priority.fromLevel(priority),
    projectId = projectId,
    parentId = parentId,
    spawnedFromId = spawnedFromId,
    dueDate = dueDate?.let { LocalDate.ofEpochDay(it) },
    dueTime = dueTime?.let { LocalTime.ofSecondOfDay(it.toLong()) },
    reminderTime = reminderTime?.let { LocalTime.ofSecondOfDay(it.toLong()) },
    completedAt = completedAt?.let { Instant.ofEpochMilli(it) },
    createdAt = Instant.ofEpochMilli(createdAt),
    sortOrder = sortOrder,
    recurrence = RecurrenceCodec.decode(recurrence),
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    notes = notes,
    priority = priority.level,
    projectId = projectId,
    parentId = parentId,
    spawnedFromId = spawnedFromId,
    dueDate = dueDate?.toEpochDay(),
    dueTime = dueTime?.toSecondOfDay(),
    reminderTime = reminderTime?.toSecondOfDay(),
    completedAt = completedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
    sortOrder = sortOrder,
    recurrence = RecurrenceCodec.encode(recurrence),
)

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["taskId"], name = "idx_attachments_task"),
        // Not decorative — the blob-reclaim query filters on sha256 and runs on every delete.
        Index(value = ["sha256"], name = "idx_attachments_sha"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    /** [AttachmentKind] by name, not ordinal — reordering the enum must not rewrite history. */
    val kind: String,
    val name: String,
    val mimeType: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    val url: String? = null,
    /** Epoch millis, matching every other instant in this file. */
    val createdAt: Long = 0L,
    val sortOrder: Int = 0,
)

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    taskId = taskId,
    // Falls back from the bytes rather than throwing, the rule RecurrenceCodec already follows:
    // an unrecognised kind should still render as something rather than crash the whole list.
    kind = AttachmentKind.entries.firstOrNull { it.name == kind }
        ?: if (sha256 != null) AttachmentKind.FILE else AttachmentKind.LINK,
    name = name,
    mimeType = mimeType,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    url = url,
    createdAt = Instant.ofEpochMilli(createdAt),
    sortOrder = sortOrder,
)

fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    taskId = taskId,
    kind = kind.name,
    name = name,
    mimeType = mimeType,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    url = url,
    createdAt = createdAt.toEpochMilli(),
    sortOrder = sortOrder,
)
