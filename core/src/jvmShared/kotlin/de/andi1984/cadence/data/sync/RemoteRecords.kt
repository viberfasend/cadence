package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * What a task and a project look like on the wire.
 *
 * The published shape, not the storage shape (ADR 0002, decision 5): a date is a `date`, a time
 * is a `time`, an instant is a `timestamptz` and recurrence is an object — deliberately not the
 * epoch-day integers and the packed `v1;key=value` string `SqlDelightStores` keeps. `20309` means
 * nothing in the Supabase table editor and less to a future web client.
 *
 * These are new types rather than `BackupCodec`'s DTOs reused, on purpose. The backup file is a
 * published contract; a column renamed in Postgres must not be able to change the shape of an
 * exported file. Twenty duplicated field names is the price of that independence.
 *
 * `user_id` is absent by design: the column defaults to `auth.uid()`, so leaving it out of the
 * payload is what files the row under the signed-in account, and an upsert that does not mention
 * it cannot move a row to somebody else either. `server_updated_at` travels as null on the way
 * out — the trigger overwrites it with the server's own clock before the row is stored.
 */
@Serializable
data class RemoteTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    /** 1…4, matching [Priority.level]. */
    val priority: Int = Priority.DEFAULT.level,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("spawned_from_id") val spawnedFromId: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("reminder_time") val reminderTime: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val recurrence: RemoteRecurrence? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    /** The server's clock. Read on the way in, ignored on the way out. */
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

@Serializable
data class RemoteProject(
    val id: String,
    val name: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

@Serializable
data class RemoteRecurrence(
    val mode: String = RecurrenceMode.SCHEDULE.name,
    val interval: Int = 1,
    val unit: String = RecurrenceUnit.WEEK.name,
    /** `MONDAY`, `THURSDAY`, … as in [DayOfWeek]. */
    @SerialName("days_of_week") val daysOfWeek: List<String> = emptyList(),
    @SerialName("monthly_mode") val monthlyMode: String = MonthlyMode.DAY_OF_MONTH.name,
    @SerialName("day_of_month") val dayOfMonth: Int? = null,
    @SerialName("nth_week") val nthWeek: Int? = null,
    @SerialName("nth_day_of_week") val nthDayOfWeek: String? = null,
    @SerialName("keep_missed") val keepMissed: Boolean = true,
)

fun Task.toRemote() = RemoteTask(
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
    recurrence = recurrence?.toRemote(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

/**
 * One incoming row, as this device would store it.
 *
 * Every timestamp is truncated to the millisecond on the way in for the same reason it is on the
 * way out (ADR 0002, decision 8): Postgres keeps microseconds, this database keeps milliseconds,
 * and a row that came back strictly newer than the copy it was made from would be merged over
 * its own local version, restamped, pushed again, and never settle.
 *
 * A row whose timestamps are unreadable is not dropped — the same rule the backup codec follows,
 * because losing a task over one malformed field is worse than an epoch date on it.
 */
fun RemoteTask.toDomain() = Task(
    id = id,
    title = title,
    notes = notes?.takeIf { it.isNotBlank() },
    priority = Priority.fromLevel(priority),
    projectId = projectId?.takeIf { it.isNotBlank() },
    parentId = parentId?.takeIf { it.isNotBlank() },
    spawnedFromId = spawnedFromId?.takeIf { it.isNotBlank() },
    dueDate = dueDate.parseOrNull { LocalDate.parse(it) },
    dueTime = dueTime.parseOrNull { LocalTime.parse(it) },
    reminderTime = reminderTime.parseOrNull { LocalTime.parse(it) },
    completedAt = completedAt.parseInstantOrNull(),
    createdAt = createdAt.parseInstantOrNull() ?: Instant.EPOCH,
    sortOrder = sortOrder,
    recurrence = recurrence?.toDomain(),
    updatedAt = updatedAt.parseInstantOrNull() ?: Instant.EPOCH,
    deletedAt = deletedAt.parseInstantOrNull(),
)

fun Project.toRemote() = RemoteProject(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

fun RemoteProject.toDomain() = Project(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId?.takeIf { it.isNotBlank() },
    sortOrder = sortOrder,
    updatedAt = updatedAt.parseInstantOrNull() ?: Instant.EPOCH,
    deletedAt = deletedAt.parseInstantOrNull(),
)

private fun RecurrenceRule.toRemote() = RemoteRecurrence(
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

private fun RemoteRecurrence.toDomain() = RecurrenceRule(
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

/**
 * PostgREST renders a `timestamptz` with an offset (`…+00:00`), which `Instant.parse` refuses —
 * it wants a `Z`. Both spellings are accepted here, since the rows this app itself wrote come
 * back in whichever form the server chooses.
 */
private fun String?.parseInstantOrNull(): Instant? = parseOrNull {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrElse { _ -> Instant.parse(it) }
        .truncatedTo(ChronoUnit.MILLIS)
}

private fun <T> String?.parseOrNull(parse: (String) -> T): T? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }
