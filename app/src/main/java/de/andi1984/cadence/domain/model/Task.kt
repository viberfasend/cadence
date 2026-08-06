package de.andi1984.cadence.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Task(
    val id: Long = 0L,
    val title: String,
    val notes: String? = null,
    val priority: Priority = Priority.DEFAULT,
    val projectId: Long? = null,
    /**
     * The task this one is a step of, or null for a task that stands on its own.
     *
     * Nesting is one level deep on purpose: a subtask never becomes a parent itself, so a
     * checklist stays a checklist instead of turning into a second project tree.
     */
    val parentId: Long? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** Time of day to remind, on the due day. */
    val reminderTime: LocalTime? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
    val recurrence: RecurrenceRule? = null,
) {
    val isDone: Boolean get() = completedAt != null

    val isInbox: Boolean get() = projectId == null

    val isSubtask: Boolean get() = parentId != null

    fun isOverdue(today: LocalDate): Boolean =
        !isDone && dueDate != null && dueDate.isBefore(today)

    fun isDueOn(day: LocalDate): Boolean = dueDate == day
}

/** How much of a task's checklist is finished — "2/5" on a row, a bar on the detail screen. */
data class SubtaskProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    val isComplete: Boolean get() = total > 0 && done == total
}
