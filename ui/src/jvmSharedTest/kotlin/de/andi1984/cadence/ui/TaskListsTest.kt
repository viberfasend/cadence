package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * What each screen actually draws.
 *
 * These rules used to live inside the `@Composable`s, where nothing could reach them: `:ui` has
 * no Compose test runtime, so "which band does this task land in" was only ever checked by
 * looking at the app. Behind [taskList] they are a literal state and an assertion, like every
 * other derivation in [CadenceUiStateTest].
 *
 * The cases worth pinning are the ones that differ *between* views, since that is where a screen
 * used to be free to disagree with its neighbour: which views hide a completed task, which sort
 * a band rather than the whole list, and which band catches a task whose section is not drawn.
 */
class TaskListsTest {

    private val today = LocalDate.of(2026, 8, 21)

    private fun task(
        id: String,
        title: String = id,
        notes: String? = null,
        projectId: String? = null,
        sectionId: String? = null,
        parentId: String? = null,
        due: LocalDate? = null,
        done: Boolean = false,
        priority: Priority = Priority.DEFAULT,
        sortOrder: Int = 0,
        tagIds: List<String> = emptyList(),
    ) = Task(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        sectionId = sectionId,
        tagIds = tagIds,
        parentId = parentId,
        dueDate = due,
        completedAt = if (done) Instant.EPOCH else null,
        priority = priority,
        sortOrder = sortOrder,
    )

    private fun state(
        tasks: List<Task> = emptyList(),
        projects: List<Project> = emptyList(),
        sections: List<Section> = emptyList(),
        tags: List<Tag> = emptyList(),
        showCompleted: Boolean = false,
        sortMode: SortMode = SortMode.IMPORTANCE,
    ) = CadenceUiState(
        tasks = tasks,
        projects = projects,
        sections = sections,
        tags = tags,
        settings = CadenceSettings(showCompleted = showCompleted, sortMode = sortMode),
    )

    private fun TaskBand.ids() = rows.map { it.task.id }

    private fun TaskList.ids() = rows.map { it.task.id }

    // ── Today ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Today keeps overdue in its own band above what is due today`() {
        val list = state(
            tasks = listOf(
                task("late", due = today.minusDays(3), priority = Priority.DEFAULT),
                task("now", due = today, priority = Priority.P1),
            ),
        ).taskList(TaskView.Today, today)

        // The urgent task due today does not climb into the overdue block: the bands are
        // ordered by what they mean, and only their contents are sorted by importance.
        assertEquals(listOf("late"), list.band(BandHeading.Overdue)?.ids())
        assertEquals(listOf("now"), list.band(BandHeading.None)?.ids())
    }

    @Test
    fun `Today hides a completed task unless the setting says otherwise`() {
        val tasks = listOf(task("open", due = today), task("done", due = today, done = true))

        assertEquals(listOf("open"), state(tasks).taskList(TaskView.Today, today).ids())
        assertEquals(
            listOf("open", "done"),
            state(tasks, showCompleted = true).taskList(TaskView.Today, today).ids(),
        )
    }

    @Test
    fun `Today drops the overdue band entirely when nothing is overdue`() {
        val list = state(tasks = listOf(task("now", due = today))).taskList(TaskView.Today, today)

        assertEquals(null, list.band(BandHeading.Overdue))
        assertEquals(1, list.bands.size)
    }

    @Test
    fun `Today counts what is open across both bands`() {
        val list = state(
            tasks = listOf(
                task("late", due = today.minusDays(1)),
                task("now", due = today),
                task("done", due = today, done = true),
            ),
            showCompleted = true,
        ).taskList(TaskView.Today, today)

        assertEquals(2, list.openCount)
    }

    // ── Inbox ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Inbox shows root tasks only, and splices the steps of an expanded parent below it`() {
        val tasks = listOf(
            task("parent"),
            task("step", parentId = "parent"),
            task("loner", sortOrder = 1),
        )

        assertEquals(listOf("parent", "loner"), state(tasks).taskList(TaskView.Inbox, today).ids())
        assertEquals(
            listOf("parent", "step", "loner"),
            state(tasks).taskList(TaskView.Inbox, today, expandedIds = setOf("parent")).ids(),
        )
    }

    @Test
    fun `Inbox leaves out anything filed under a project`() {
        val list = state(
            tasks = listOf(task("loose"), task("filed", projectId = "p")),
            projects = listOf(Project(id = "p", name = "Home")),
        ).taskList(TaskView.Inbox, today)

        assertEquals(listOf("loose"), list.ids())
    }

    // ── Upcoming ─────────────────────────────────────────────────────────────────────

    @Test
    fun `Upcoming bands by day, in date order, and never reaches back to today`() {
        val list = state(
            tasks = listOf(
                task("in-three", due = today.plusDays(3)),
                task("tomorrow", due = today.plusDays(1)),
                task("today", due = today),
                task("undated"),
            ),
        ).taskList(TaskView.Upcoming, today)

        assertEquals(
            listOf(BandHeading.Day(today.plusDays(1)), BandHeading.Day(today.plusDays(3))),
            list.bands.map { it.heading },
        )
        assertEquals(listOf("tomorrow", "in-three"), list.ids())
    }

    @Test
    fun `Upcoming never shows a completed task, whatever the setting says`() {
        val list = state(
            tasks = listOf(
                task("open", due = today.plusDays(1)),
                task("done", due = today.plusDays(1), done = true),
            ),
            showCompleted = true,
        ).taskList(TaskView.Upcoming, today)

        assertEquals(listOf("open"), list.ids())
    }

    // ── Project ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a project without sections splits overdue from the rest`() {
        val list = state(
            tasks = listOf(
                task("late", projectId = "p", due = today.minusDays(1)),
                task("rest", projectId = "p"),
            ),
            projects = listOf(Project(id = "p", name = "Home")),
        ).taskList(TaskView.Project("p"), today)

        assertEquals(
            listOf(BandHeading.Overdue, BandHeading.Everything),
            list.bands.map { it.heading },
        )
        assertEquals(listOf("late"), list.bands[0].ids())
        assertEquals(listOf("rest"), list.bands[1].ids())
    }

    @Test
    fun `a project with sections splits by heading instead, overdue staying in its band`() {
        val band = section("band", "p")
        val list = state(
            tasks = listOf(
                task("late-in-band", projectId = "p", sectionId = "band", due = today.minusDays(1)),
                task("plain", projectId = "p"),
            ),
            projects = listOf(Project(id = "p", name = "Home")),
            sections = listOf(band),
        ).taskList(TaskView.Project("p"), today)

        assertEquals(
            listOf(BandHeading.Ungrouped, BandHeading.Named(band)),
            list.bands.map { it.heading },
        )
        assertEquals(listOf("plain"), list.bands[0].ids())
        assertEquals(listOf("late-in-band"), list.bands[1].ids())
    }

    @Test
    fun `the ungrouped band catches a task filed under a section this list never draws`() {
        // The regression this pins: a project's screen also shows the tasks of its subprojects,
        // and one of those may sit under a section of its *own* project. Matching only nulls
        // would leave it in no band at all and drop it off the screen.
        val parentBand = section("parent-band", "p")
        val list = state(
            tasks = listOf(
                task("in-child-band", projectId = "child", sectionId = "child-band"),
                task("in-parent-band", projectId = "p", sectionId = "parent-band"),
            ),
            projects = listOf(
                Project(id = "p", name = "Home"),
                Project(id = "child", name = "Kitchen", parentId = "p"),
            ),
            sections = listOf(parentBand, section("child-band", "child")),
        ).taskList(TaskView.Project("p"), today)

        assertEquals(listOf("in-child-band"), list.bands[0].ids())
        assertEquals(listOf("in-parent-band"), list.bands[1].ids())
    }

    @Test
    fun `the ungrouped band keeps its heading when it is empty`() {
        val band = section("band", "p")
        val list = state(
            tasks = listOf(task("filed", projectId = "p", sectionId = "band")),
            projects = listOf(Project(id = "p", name = "Home")),
            sections = listOf(band),
        ).taskList(TaskView.Project("p"), today)

        assertEquals(BandHeading.Ungrouped, list.bands[0].heading)
        assertTrue(list.bands[0].isEmpty)
    }

    @Test
    fun `a project hides completed tasks unless the setting says otherwise`() {
        val tasks = listOf(
            task("open", projectId = "p"),
            task("done", projectId = "p", done = true),
        )
        val projects = listOf(Project(id = "p", name = "Home"))

        assertEquals(
            listOf("open"),
            state(tasks, projects).taskList(TaskView.Project("p"), today).ids(),
        )
        assertEquals(
            listOf("open", "done"),
            state(tasks, projects, showCompleted = true)
                .taskList(TaskView.Project("p"), today).ids(),
        )
    }

    @Test
    fun `every band of a project's list carries its own key`() {
        val list = state(
            tasks = listOf(task("a", projectId = "p"), task("b", projectId = "p", sectionId = "band")),
            projects = listOf(Project(id = "p", name = "Home")),
            sections = listOf(section("band", "p")),
        ).taskList(TaskView.Project("p"), today)

        assertEquals(list.bands.size, list.bands.map { it.key }.toSet().size)
    }

    // ── Search ───────────────────────────────────────────────────────────────────────

    @Test
    fun `search matches title and notes, and reaches completed tasks on purpose`() {
        val state = state(
            tasks = listOf(
                task("title-hit", title = "Buy milk"),
                task("notes-hit", title = "Errand", notes = "the MILK is for the cake"),
                task("done-hit", title = "milk run", done = true),
                task("miss", title = "Call Ada"),
            ),
        )

        assertEquals(
            setOf("title-hit", "notes-hit", "done-hit"),
            state.taskList(TaskView.Search("milk"), today).ids().toSet(),
        )
    }

    @Test
    fun `a blank query is nobody searching, not everybody matching`() {
        val list = state(tasks = listOf(task("a"))).taskList(TaskView.Search("   "), today)

        assertTrue(list.isEmpty)
        assertEquals(emptyList<TaskBand>(), list.bands)
    }

    @Test
    fun `search reads labels too, so a phone can reach a tag by typing`() {
        val state = state(
            tasks = listOf(
                task("labelled", title = "Post the forms", tagIds = listOf("g1")),
                task("named", title = "Errand list"),
                task("miss", title = "Call Ada"),
            ),
            tags = listOf(Tag(id = "g1", name = "Errand")),
        )

        // Both, without the at-sign: "errand" and "@errand" are the same thought, and the
        // desktop's command palette is the only other thing in the app that answers the second.
        assertEquals(
            setOf("labelled", "named"),
            state.taskList(TaskView.Search("errand"), today).ids().toSet(),
        )
    }

    @Test
    fun `a leading at-sign narrows a search to labels`() {
        val state = state(
            tasks = listOf(
                task("labelled", title = "Post the forms", tagIds = listOf("g1")),
                task("named", title = "Errand list"),
            ),
            tags = listOf(Tag(id = "g1", name = "Errand")),
        )

        // The row whose *title* says errand is not wearing the label, so "@errand" leaves it out.
        assertEquals(
            listOf("labelled"),
            state.taskList(TaskView.Search("@errand"), today).ids(),
        )
    }

    @Test
    fun `a tag with a space in its name is reachable by its handle`() {
        val state = state(
            tasks = listOf(task("labelled", title = "Draft the memo", tagIds = listOf("g1"))),
            tags = listOf(Tag(id = "g1", name = "Deep Work")),
        )

        assertEquals(listOf("labelled"), state.taskList(TaskView.Search("@deepwork"), today).ids())
    }

    @Test
    fun `an at-sign on its own is somebody mid-word, not a search for every label`() {
        val state = state(
            tasks = listOf(task("labelled", title = "Post the forms", tagIds = listOf("g1"))),
            tags = listOf(Tag(id = "g1", name = "Errand")),
        )

        assertTrue(state.taskList(TaskView.Search("@"), today).isEmpty)
    }

    // ── Sorting ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the sort mode reaches inside a band`() {
        val tasks = listOf(
            task("low", projectId = "p", priority = Priority.P4, sortOrder = 0),
            task("high", projectId = "p", priority = Priority.P1, sortOrder = 1),
        )
        val projects = listOf(Project(id = "p", name = "Home"))

        assertEquals(
            listOf("high", "low"),
            state(tasks, projects).taskList(TaskView.Project("p"), today).ids(),
        )
        assertEquals(
            listOf("low", "high"),
            state(tasks, projects, sortMode = SortMode.MANUAL)
                .taskList(TaskView.Project("p"), today).ids(),
        )
    }

    private fun section(id: String, projectId: String, sortOrder: Int = 0) =
        Section(id = id, projectId = projectId, name = id, sortOrder = sortOrder)

    // ── A tag's list ─────────────────────────────────────────────────────────────────

    @Test
    fun `a tag's list is one band, across projects`() {
        val state = state(
            tasks = listOf(
                task("a", projectId = "p1", tagIds = listOf("g1")),
                task("b", projectId = "p2", tagIds = listOf("g1")),
                task("c", projectId = "p1"),
            ),
            projects = listOf(Project(id = "p1", name = "One"), Project(id = "p2", name = "Two")),
            tags = listOf(Tag(id = "g1", name = "Errand")),
        )

        val list = state.taskList(TaskView.Tag("g1"), today)

        assertEquals(1, list.bands.size)
        assertEquals(listOf("a", "b"), list.ids())
    }

    /**
     * `showCompleted` applies, unlike Search: a tag list is a place you work from, so a finished
     * errand should leave it the way it leaves the Inbox.
     */
    @Test
    fun `a done task leaves a tag's list unless showCompleted is on`() {
        val tasks = listOf(
            task("open", tagIds = listOf("g1")),
            task("done", tagIds = listOf("g1"), done = true),
        )

        assertEquals(
            listOf("open"),
            state(tasks = tasks).taskList(TaskView.Tag("g1"), today).ids(),
        )
        assertEquals(
            listOf("open", "done"),
            state(tasks = tasks, showCompleted = true).taskList(TaskView.Tag("g1"), today).ids(),
        )
    }

    /** Like the container views and unlike Today: a daily `@errand` must not stack up a
     *  struck-through copy per day in a list that is not scoped to a date. */
    @Test
    fun `a replaced recurring occurrence is dropped from a tag's list`() {
        val state = state(
            tasks = listOf(
                task("first", tagIds = listOf("g1"), done = true),
                task("second", tagIds = listOf("g1")).copy(spawnedFromId = "first"),
            ),
            showCompleted = true,
        )

        assertEquals(listOf("second"), state.taskList(TaskView.Tag("g1"), today).ids())
    }

    @Test
    fun `a tag nothing wears draws an empty list rather than everything`() {
        val state = state(tasks = listOf(task("a")))

        assertTrue(state.taskList(TaskView.Tag("g1"), today).isEmpty)
    }
}
