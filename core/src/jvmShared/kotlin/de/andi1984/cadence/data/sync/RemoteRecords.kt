package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.parseOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * What a task, a project, a section and a tag look like on the wire.
 *
 * The published shape, not the storage shape (ADR 0002, decision 5): a date is a `date`, a time
 * is a `time`, an instant is a `timestamptz` and recurrence is an object — deliberately not the
 * epoch-day integers and the packed `v1;key=value` string `SqlDelightStores` keeps. `20309` means
 * nothing in the Neon table editor and less to a future web client.
 *
 * These are new types rather than `BackupCodec`'s DTOs reused, on purpose. The backup file is a
 * published contract; a column renamed in Postgres must not be able to change the shape of an
 * exported file. Twenty duplicated field names is the price of that independence.
 *
 * `user_id` is absent by design: the column defaults to `(auth.user_id())::uuid`, so leaving it out of the
 * payload is what files the row under the signed-in account, and an upsert that does not mention
 * it cannot move a row to somebody else either. `server_updated_at` travels as null on the way
 * out — the trigger overwrites it with the server's own clock before the row is stored.
 */
/**
 * How those records are written and read, and the reason `encodeDefaults` is on.
 *
 * kotlinx.serialization leaves out a property that still holds its declared default, and the
 * DTOs below are full of them — `sort_order = 0`, the default priority, every nullable field that
 * happens to be null. Postgres is not reading Kotlin defaults, so an omitted key means three
 * different kinds of wrong at once:
 *
 * - `sort_order integer not null` has no database default, so an insert without the column is a
 *   not-null violation and the whole round fails.
 * - An upsert only updates the columns it mentions, so *clearing* a field — dropping a due date,
 *   emptying the notes — would arrive as "no opinion" and the server would keep the old value.
 * - PostgREST rejects a bulk insert whose objects do not all carry the same keys (`PGRST102`), and
 *   whether two tasks in one batch agree would otherwise depend on which fields they filled in.
 *
 * Writing every field always is what makes the payload mean what it says. `server_updated_at`
 * therefore travels as an explicit null, which is harmless: the `BEFORE INSERT OR UPDATE` trigger
 * overwrites it with the server's clock before the not-null constraint is ever checked.
 */
internal val SyncJson: Json = Json {
    encodeDefaults = true
    // A column added server-side must not break an older install — the same courtesy the backup
    // format extends to older files.
    ignoreUnknownKeys = true
}

@Serializable
data class RemoteTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    /** 1…4, matching [Priority.level]. */
    val priority: Int = Priority.DEFAULT.level,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("section_id") val sectionId: String? = null,
    /**
     * The tags on this task, as a `uuid[]` column — a real array on the wire, deliberately not
     * the comma-packed string the local column holds. The published shape is the readable one
     * (ADR 0002, decision 5), and Postgres can index and filter an array; it cannot do either
     * with `'a,b,c'`.
     */
    @SerialName("tag_ids") val tagIds: List<String> = emptyList(),
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
data class RemoteSection(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

/**
 * A tag's identity. Which tasks wear it is `tasks.tag_ids`, not a row here — the mirror carries no
 * join table for the same reason the local schema carries none (see `Task.tagIds`).
 */
@Serializable
data class RemoteTag(
    val id: String,
    val name: String,
    @SerialName("color_hex") val colorHex: String,
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
)

fun Task.toRemote() = RemoteTask(
    id = id,
    title = title,
    notes = notes,
    priority = priority.level,
    projectId = projectId,
    sectionId = sectionId,
    tagIds = tagIds,
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
    sectionId = sectionId?.takeIf { it.isNotBlank() },
    tagIds = tagIds.filter { it.isNotBlank() }.distinct(),
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

fun Section.toRemote() = RemoteSection(
    id = id,
    projectId = projectId,
    name = name,
    sortOrder = sortOrder,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

fun RemoteSection.toDomain() = Section(
    id = id,
    projectId = projectId,
    name = name,
    sortOrder = sortOrder,
    updatedAt = updatedAt.parseInstantOrNull() ?: Instant.EPOCH,
    deletedAt = deletedAt.parseInstantOrNull(),
)

fun Tag.toRemote() = RemoteTag(
    id = id,
    name = name,
    colorHex = colorHex,
    sortOrder = sortOrder,
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

fun RemoteTag.toDomain() = Tag(
    id = id,
    name = name,
    colorHex = colorHex,
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
)

private fun RemoteRecurrence.toDomain() = RecurrenceRule.fromNames(
    mode = mode,
    interval = interval,
    unit = unit,
    daysOfWeek = daysOfWeek,
    monthlyMode = monthlyMode,
    dayOfMonth = dayOfMonth,
    nthWeek = nthWeek,
    nthDayOfWeek = nthDayOfWeek,
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
