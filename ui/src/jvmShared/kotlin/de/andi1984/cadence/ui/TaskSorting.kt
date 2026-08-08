package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.SortMode
import java.time.LocalDate
import java.time.LocalTime

private val FAR_FUTURE: LocalDate = LocalDate.of(4000, 1, 1)
private val END_OF_DAY: LocalTime = LocalTime.of(23, 59)

/**
 * Importance first, due date breaks ties — the one ordering rule the whole app is built on.
 * The other two modes are the explicit opt-outs offered by the sort chips.
 */
fun List<Task>.sortedFor(mode: SortMode): List<Task> = when (mode) {
    SortMode.IMPORTANCE -> sortedWith(
        compareBy<Task> { it.isDone }
            .thenBy { it.priority.level }
            .thenBy { it.dueDate ?: FAR_FUTURE }
            .thenBy { it.dueTime ?: END_OF_DAY }
            .thenBy { it.sortOrder },
    )

    SortMode.DATE -> sortedWith(
        compareBy<Task> { it.isDone }
            .thenBy { it.dueDate ?: FAR_FUTURE }
            .thenBy { it.dueTime ?: END_OF_DAY }
            .thenBy { it.priority.level }
            .thenBy { it.sortOrder },
    )

    SortMode.MANUAL -> sortedWith(
        compareBy<Task> { it.isDone }
            .thenBy { it.sortOrder }
            .thenBy { it.id },
    )
}
