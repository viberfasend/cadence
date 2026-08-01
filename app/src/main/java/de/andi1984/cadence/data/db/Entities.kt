package de.andi1984.cadence.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorHex: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val notes: String? = null,
    val priority: Int = Priority.DEFAULT.level,
    val projectId: Long? = null,
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
    dueDate = dueDate?.toEpochDay(),
    dueTime = dueTime?.toSecondOfDay(),
    reminderTime = reminderTime?.toSecondOfDay(),
    completedAt = completedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
    sortOrder = sortOrder,
    recurrence = RecurrenceCodec.encode(recurrence),
)
