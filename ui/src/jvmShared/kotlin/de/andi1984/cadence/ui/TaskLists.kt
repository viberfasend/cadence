package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import java.time.LocalDate

/**
 * Which list a screen is drawing.
 *
 * The views the app has, enumerated — so "what lists exist" is something the compiler answers
 * rather than something spread across six `@Composable`s. [taskList] turns one of these into the
 * bands a screen draws; the screen decides what a band *looks* like and nothing else.
 */
sealed interface TaskView {

    /** Overdue above, due today below — the two bands the Today screen is made of. */
    data object Today : TaskView

    /** Everything with no project, parents speaking for their steps. */
    data object Inbox : TaskView

    /** Open work after today, one band per day. */
    data object Upcoming : TaskView

    /** A project and its subprojects, banded by section when the project has any. */
    data class Project(val projectId: String) : TaskView

    /** Title and notes, matched case-insensitively. A blank query matches nothing. */
    data class Search(val query: String) : TaskView

    /**
     * Everything wearing one tag, newest work first by the usual sort.
     *
     * Deliberately not banded and deliberately not scoped to a container: a tag cuts across
     * projects, so there is no heading structure to inherit from. It behaves like [Search] in
     * every other way, including showing subtasks in their own right — a step labelled `@errand`
     * is an errand.
     */
    data class Tag(val tagId: String) : TaskView
}

/**
 * What a band's heading says — the kind, never the words.
 *
 * Prose stays in `ui/format` and `composeResources` (the project rule: no user-visible string in
 * Kotlin), so this names the heading and the screen resolves it.
 */
sealed interface BandHeading {

    /** No heading at all — a single-band list, or the band a screen titles itself. */
    data object None : BandHeading

    /** The overdue block: `project_section_overdue`, drawn in the error colour. */
    data object Overdue : BandHeading

    /** Everything that is not overdue, under `project_section_all`. */
    data object Everything : BandHeading

    /** Tasks in a project that no drawn section holds — `sections_ungrouped`. */
    data object Ungrouped : BandHeading

    /** One of a project's own sections, drawn with its rename/delete controls. */
    data class Named(val section: Section) : BandHeading

    /** A day in the agenda. The count is [TaskBand.rows] size; the screen formats the date. */
    data class Day(val date: LocalDate) : BandHeading
}

/**
 * One heading and the rows under it, ready to draw.
 *
 * A band that is present but empty is deliberate — the ungrouped band keeps its heading even
 * with nothing in it, so a project's list never reads as if a task could only live under a
 * section. Bands that should disappear when empty are simply not returned.
 */
data class TaskBand(
    val heading: BandHeading,
    val rows: List<TaskListRow>,
    /** Stable prefix for the `LazyColumn` keys of this band's rows. */
    val key: String,
) {
    val tasks: List<Task> get() = rows.map { it.task }

    val isEmpty: Boolean get() = rows.isEmpty()

    val isNotEmpty: Boolean get() = rows.isNotEmpty()

    val openCount: Int get() = rows.count { !it.task.isDone }
}

/**
 * Everything one screen draws, in order.
 *
 * This is the whole interface between "what the app decides to show" and "how it looks". The
 * rules that used to sit inside each screen — the completed filter, the sort, which band a task
 * falls in, where a subtask is spliced in — live behind [taskList] and are reachable from
 * `jvmSharedTest` without a Compose runtime.
 */
data class TaskList(val bands: List<TaskBand>) {

    val rows: List<TaskListRow> get() = bands.flatMap { it.rows }

    val tasks: List<Task> get() = rows.map { it.task }

    val isEmpty: Boolean get() = bands.all { it.isEmpty }

    val openCount: Int get() = tasks.count { !it.isDone }

    /** The first band with this heading, for a screen that draws one band differently. */
    fun band(heading: BandHeading): TaskBand? = bands.firstOrNull { it.heading == heading }

    companion object {
        val EMPTY = TaskList(emptyList())
    }
}

/**
 * The list [view] draws, filtered, sorted and banded.
 *
 * One entry point for every task list in the app. Each view keeps exactly the rules it had while
 * they were spread across the screens, and the differences between them are deliberate rather
 * than accidental:
 *
 * - **Completed tasks** are dropped from [TaskView.Inbox] and [TaskView.Project] unless
 *   `settings.showCompleted` is on. [TaskView.Today] applies it to the due-today band only —
 *   an overdue task is open by definition ([Task.isOverdue]) — [TaskView.Upcoming] never shows
 *   a done task, and [TaskView.Search] never hides one, because search is meant to reach
 *   history.
 * - **The sort** is `settings.sortMode` everywhere ([sortedFor]), applied inside a band: the
 *   bands themselves are ordered by what they mean, not by importance.
 * - **Subtasks** are spliced in under whichever parent is in [expandedIds]
 *   ([CadenceUiState.expandedRows]). The container views start from
 *   [CadenceUiState.rootTasks]; the date-driven ones do not, because a dated subtask is work
 *   for that day in its own right.
 *
 * @param today the day the list is drawn for — passed in rather than read from the clock, so a
 *   test can name it.
 * @param expandedIds parents whose steps are shown inline. Screen-remembered state; views that
 *   never expand a row simply pass nothing.
 */
fun CadenceUiState.taskList(
    view: TaskView,
    today: LocalDate,
    expandedIds: Set<String> = emptySet(),
): TaskList = when (view) {
    TaskView.Today -> todayList(today)
    TaskView.Inbox -> inboxList(expandedIds)
    TaskView.Upcoming -> upcomingList(today)
    is TaskView.Project -> projectList(view.projectId, today, expandedIds)
    is TaskView.Search -> searchList(view.query)
    is TaskView.Tag -> tagList(view.tagId)
}

/**
 * Overdue first, then what is due today.
 *
 * The two bands are separate lists rather than one sorted run: the overdue band is the red block
 * pinned above the day, and a P1 due today must not push its way into it.
 */
private fun CadenceUiState.todayList(today: LocalDate): TaskList {
    val mode = settings.sortMode
    val overdue = overdue(today).sortedFor(mode)
    val dueToday = tasks
        .filter { it.isDueOn(today) && (settings.showCompleted || !it.isDone) }
        .sortedFor(mode)
    return TaskList(
        buildList {
            if (overdue.isNotEmpty()) add(band(BandHeading.Overdue, overdue, key = "overdue"))
            add(band(BandHeading.None, dueToday, key = "due-today"))
        },
    )
}

private fun CadenceUiState.inboxList(expandedIds: Set<String>): TaskList {
    val inbox = inboxTasks()
        .filter { settings.showCompleted || !it.isDone }
        .sortedFor(settings.sortMode)
    return TaskList(listOf(band(BandHeading.None, inbox, key = "inbox", expandedIds = expandedIds)))
}

/** Open work due after today, one band per day, days in order. */
private fun CadenceUiState.upcomingList(today: LocalDate): TaskList {
    val upcoming = tasks
        .filter { task -> !task.isDone && task.dueDate?.isAfter(today) == true }
        .sortedFor(settings.sortMode)
    val byDay = upcoming.groupBy { it.dueDate!! }.toSortedMap()
    return TaskList(
        byDay.map { (date, tasks) -> band(BandHeading.Day(date), tasks, key = "d-$date") },
    )
}

/**
 * A project's list, banded the way the project is filed.
 *
 * Without sections it splits by urgency — overdue on top, the rest below. With them it splits by
 * *where the user filed the work* instead, because two splits at once would put a task's own
 * section three headings away from it. An overdue task still reads as overdue inside its band;
 * it just does not leave it.
 *
 * The ungrouped band catches more than `sectionId == null`, and keeps its heading when empty:
 * a project's screen also shows the tasks of its subprojects, and one of those may be filed
 * under a section of *its own* project — a heading this list never draws. Matching only nulls
 * would leave that task in no band at all and drop it off the screen, the same failure the
 * "deleting a project never silently hides tasks" rule exists to prevent.
 */
private fun CadenceUiState.projectList(
    projectId: String,
    today: LocalDate,
    expandedIds: Set<String>,
): TaskList {
    val tasks = tasksIn(projectId)
        .filter { settings.showCompleted || !it.isDone }
        .sortedFor(settings.sortMode)
    val sections = sectionsIn(projectId)

    if (sections.isEmpty()) {
        val overdue = tasks.filter { it.isOverdue(today) }
        val rest = tasks.filterNot { it.isOverdue(today) }
        return TaskList(
            buildList {
                if (overdue.isNotEmpty()) {
                    add(band(BandHeading.Overdue, overdue, key = "o", expandedIds = expandedIds))
                }
                if (rest.isNotEmpty()) {
                    add(band(BandHeading.Everything, rest, key = "a", expandedIds = expandedIds))
                }
            },
        )
    }

    val drawn = sections.mapTo(mutableSetOf()) { it.id }
    val ungrouped = tasks.filter { it.sectionId == null || it.sectionId !in drawn }
    return TaskList(
        buildList {
            add(band(BandHeading.Ungrouped, ungrouped, key = "u", expandedIds = expandedIds))
            sections.forEach { section ->
                add(
                    band(
                        BandHeading.Named(section),
                        tasks.filter { it.sectionId == section.id },
                        key = section.id,
                        expandedIds = expandedIds,
                    ),
                )
            }
        },
    )
}

/**
 * Title, notes and labels, case-insensitively. A blank query is not a search for everything — it
 * is a search nobody has typed yet, and it matches nothing.
 *
 * Completed tasks are deliberately included: search is the one view meant to reach history.
 *
 * **A leading `@` narrows to labels**, the same character the quick-add line and the desktop's
 * command palette already take for one. Without it search still reads them, because "errand" and
 * "@errand" are the same thought and only one of the two is a thing you can type by accident.
 * This is the whole way a phone reaches a tag by typing: the palette that answers `@` is the
 * desktop's, and a search that ignored labels would leave Android with no way to ask the question
 * at all.
 */
private fun CadenceUiState.searchList(query: String): TaskList {
    val typed = query.trim()
    if (typed.isBlank()) return TaskList.EMPTY
    // `@` alone is someone who has started typing, not a search for every labelled task.
    val handle = typed.removePrefix("@").takeIf { typed.startsWith('@') && it.isNotBlank() }
    val results = tasks
        .filter { task ->
            val labels = tagsOf(task)
            if (handle != null) {
                labels.any { it.matchesTyped(handle) }
            } else {
                task.title.contains(typed, ignoreCase = true) ||
                    task.notes?.contains(typed, ignoreCase = true) == true ||
                    labels.any { it.matchesTyped(typed) }
            }
        }
        .sortedFor(settings.sortMode)
    return TaskList(listOf(band(BandHeading.None, results, key = "s")))
}

/**
 * Whether a label answers to what someone typed — its name or its spaceless handle, containing
 * rather than starting with, because search is where a half-remembered word is the whole point.
 *
 * Deliberately looser than [de.andi1984.cadence.domain.model.matchingHandle], which has to pick
 * exactly one tag or create a new one; a search that returns two lists' worth of rows costs the
 * reader a glance, not a wrongly labelled task.
 */
private fun Tag.matchesTyped(typed: String): Boolean =
    name.contains(typed, ignoreCase = true) || handle.contains(typed, ignoreCase = true)

/**
 * Everything labelled [tagId], in one band.
 *
 * `showCompleted` applies, unlike [searchList] — a tag list is a place you work from, not a place
 * you look things up, so a finished errand should leave it the way it leaves the Inbox. A
 * superseded recurring occurrence is dropped for the same reason the container views drop one: a
 * daily `@errand` would otherwise stack up a struck-through copy per day.
 */
private fun CadenceUiState.tagList(tagId: String): TaskList {
    val tasks = tasksWithTag(tagId)
        .withoutSupersededOccurrences()
        .filter { settings.showCompleted || !it.isDone }
        .sortedFor(settings.sortMode)
    return TaskList(listOf(band(BandHeading.None, tasks, key = "tag-$tagId")))
}

private fun CadenceUiState.band(
    heading: BandHeading,
    tasks: List<Task>,
    key: String,
    expandedIds: Set<String> = emptySet(),
): TaskBand = TaskBand(heading = heading, rows = expandedRows(tasks, expandedIds), key = key)
