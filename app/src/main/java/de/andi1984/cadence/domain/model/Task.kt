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

    fun isOverdue(today: LocalDate): Boolean =
        !isDone && dueDate != null && dueDate.isBefore(today)

    fun isDueOn(day: LocalDate): Boolean = dueDate == day
}
