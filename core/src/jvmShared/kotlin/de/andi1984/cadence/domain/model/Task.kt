package de.andi1984.cadence.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Task(
    /** Blank until [de.andi1984.cadence.data.CadenceRepository] mints a UUIDv7 for a new task —
     *  ids are no longer assigned by storage (ADR 0001, decision 4). */
    val id: String = "",
    val title: String,
    val notes: String? = null,
    val priority: Priority = Priority.DEFAULT,
    val projectId: String? = null,
    /**
     * The [Section] of [projectId] this task is grouped under, or null for the project's
     * ungrouped band.
     *
     * Only ever meaningful together with [projectId] — a section belongs to one project — so
     * moving a task to another project clears this rather than carrying a heading that project
     * has never heard of.
     */
    val sectionId: String? = null,
    /**
     * The [Tag]s on this task, as ids, in the order they were applied.
     *
     * **Membership is an attribute of the task, not a row of its own.** There is no join table:
     * the ids are packed into a single column
     * ([de.andi1984.cadence.data.db.TagIdsCodec]) and travel with the task through sync and
     * backup like [priority] or [sectionId] do. Two consequences, both deliberate:
     *
     * - Deleting a tag costs one row, not one per task that wore it. The dangling ids stay
     *   behind and are dropped when the list is read against the live tags — the same "links are
     *   repaired rather than trusted" rule the backup codec follows. It also means reviving a
     *   tag from a backup puts it back on exactly the tasks that had it.
     * - Two devices adding a *different* tag to the *same* task while offline resolve
     *   last-writer-wins, and one of the two additions is lost. That is the trade a join table
     *   would buy back, at the price of a fifth synced table whose tombstones would outnumber
     *   the tasks — and it is the same trade the app already makes for a concurrently edited
     *   title or note.
     */
    val tagIds: List<String> = emptyList(),
    /**
     * The task this one is a step of, or null for a task that stands on its own.
     *
     * Nesting is one level deep on purpose: a subtask never becomes a parent itself, so a
     * checklist stays a checklist instead of turning into a second project tree.
     */
    val parentId: String? = null,
    /**
     * The occurrence whose completion inserted this row, for a recurring task.
     *
     * Recurrence is a chain of rows, and this is the only link between two of them: it says
     * which finished occurrence this one replaces. Reopening a completion uses it to take the
     * row that completion created back out, and the lists that are not scoped to a day use it
     * to tell a finished occurrence apart from one that is still the task's current state.
     */
    val spawnedFromId: String? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** Time of day to remind, on the due day. */
    val reminderTime: LocalTime? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
    val recurrence: RecurrenceRule? = null,
    /** Last write, local or merged in. Sync (ADR 0002) resolves conflicts by this. */
    val updatedAt: Instant = Instant.EPOCH,
    /** Tombstone: set instead of a hard delete, so sync (ADR 0002) can carry one device's delete
     *  to another's. Every delete is a stamp here, never a real `DELETE`. */
    val deletedAt: Instant? = null,
) {
    val isDone: Boolean get() = completedAt != null

    val isInbox: Boolean get() = projectId == null

    val isSubtask: Boolean get() = parentId != null

    fun isOverdue(today: LocalDate): Boolean =
        !isDone && dueDate != null && dueDate.isBefore(today)

    fun isDueOn(day: LocalDate): Boolean = dueDate == day
}

/**
 * Drops the occurrences a recurring task has already moved past.
 *
 * Completing a recurring task keeps the finished row — Today shows it as "Done 09:12" for the
 * rest of the day — and inserts the next occurrence. A list that is not scoped to a day, the
 * Inbox or a project, would therefore collect a struck-through copy of a daily task every single
 * day and read as if the task existed many times over. In those lists the open successor speaks
 * for the task and the occurrence it replaced is history.
 *
 * Only a *replaced* occurrence is dropped. The last one in a chain stays, so finishing a
 * recurring task for the final time does not make it vanish without trace.
 */
fun List<Task>.withoutSupersededOccurrences(): List<Task> {
    val replaced = mapNotNullTo(mutableSetOf()) { it.spawnedFromId }
    if (replaced.isEmpty()) return this
    return filterNot { it.isDone && it.id in replaced }
}

/** How much of a task's checklist is finished — "2/5" on a row, a bar on the detail screen. */
data class SubtaskProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    val isComplete: Boolean get() = total > 0 && done == total
}
