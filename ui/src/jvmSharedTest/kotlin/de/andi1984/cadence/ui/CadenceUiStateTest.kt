package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The derived views every screen reads. They are pure functions on a data class, so a test is a
 * literal state and an assertion — no ViewModel, no flows, no dispatcher.
 *
 * The rules worth pinning are the ones a screen would otherwise have to re-derive: which lists
 * drop a subtask, which drop a recurring occurrence that has been replaced, and which band of a
 * project's list a task filed under a subproject's own section lands in.
 */
class CadenceUiStateTest {

    private val today = LocalDate.of(2026, 8, 17)

    private fun task(
        id: String,
        title: String = id,
        projectId: String? = null,
        sectionId: String? = null,
        parentId: String? = null,
        spawnedFromId: String? = null,
        due: LocalDate? = null,
        done: Boolean = false,
        sortOrder: Int = 0,
        tagIds: List<String> = emptyList(),
    ) = Task(
        id = id,
        title = title,
        projectId = projectId,
        sectionId = sectionId,
        tagIds = tagIds,
        parentId = parentId,
        spawnedFromId = spawnedFromId,
        dueDate = due,
        completedAt = if (done) Instant.EPOCH else null,
        sortOrder = sortOrder,
    )

    private fun project(id: String, name: String = id, parentId: String? = null, sortOrder: Int = 0) =
        Project(id = id, name = name, parentId = parentId, sortOrder = sortOrder)

    private fun section(id: String, projectId: String, name: String = id, sortOrder: Int = 0) =
        Section(id = id, projectId = projectId, name = name, sortOrder = sortOrder)

    private fun tag(id: String, name: String = id, sortOrder: Int = 0) =
        Tag(id = id, name = name, sortOrder = sortOrder)

    private fun List<Task>.ids() = map { it.id }

    // ── rootTasks / superseded occurrences ───────────────────────────────────────────

    @Test
    fun `rootTasks drops subtasks — the parent speaks for its steps in a container list`() {
        val state = CadenceUiState(
            tasks = listOf(task("parent"), task("step", parentId = "parent"), task("loner")),
        )

        assertEquals(listOf("parent", "loner"), state.rootTasks().ids())
    }

    @Test
    fun `rootTasks drops a finished occurrence that a successor has replaced`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("occurrence-1", title = "Water the plants", done = true),
                task("occurrence-2", title = "Water the plants", spawnedFromId = "occurrence-1"),
            ),
        )

        assertEquals(listOf("occurrence-2"), state.rootTasks().ids())
    }

    @Test
    fun `the last occurrence of a chain stays, finished or not`() {
        // Finishing a recurring task for the final time must not make it vanish without trace:
        // only a *replaced* occurrence is dropped.
        val state = CadenceUiState(
            tasks = listOf(
                task("occurrence-1", done = true),
                task("occurrence-2", spawnedFromId = "occurrence-1", done = true),
            ),
        )

        assertEquals(listOf("occurrence-2"), state.rootTasks().ids())
    }

    @Test
    fun `the date-driven views deliberately keep both — they read state tasks directly`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("occurrence-1", due = today.minusDays(1), done = true),
                task("step", parentId = "parent", due = today.minusDays(2)),
                task("parent", due = today.plusDays(1)),
                task("occurrence-2", spawnedFromId = "occurrence-1", due = today.minusDays(1)),
            ),
        )

        // Overdue reaches a subtask in its own right, and skips the finished occurrence because
        // it is done — not because it was superseded.
        assertEquals(listOf("step", "occurrence-2"), state.overdue(today).ids())
        assertEquals(listOf("step", "parent", "occurrence-2"), state.openTasks().ids())
    }

    @Test
    fun `inboxTasks is rootTasks without a project`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("inbox"),
                task("filed", projectId = "p"),
                task("inbox-step", parentId = "inbox"),
            ),
            projects = listOf(project("p")),
        )

        assertEquals(listOf("inbox"), state.inboxTasks().ids())
    }

    // ── Subtasks ─────────────────────────────────────────────────────────────────────

    @Test
    fun `subtasks come back in the order they were added, with the id breaking a tie`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("b", parentId = "parent", sortOrder = 1),
                task("a", parentId = "parent", sortOrder = 1),
                task("first", parentId = "parent", sortOrder = 0),
                task("elsewhere", parentId = "other"),
            ),
        )

        assertEquals(listOf("first", "a", "b"), state.subtasks("parent").ids())
    }

    @Test
    fun `subtaskProgress is null for a task with no checklist at all`() {
        val state = CadenceUiState(tasks = listOf(task("parent")))

        assertNull(state.subtaskProgress("parent"))
    }

    @Test
    fun `subtaskProgress counts the finished steps`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("parent"),
                task("one", parentId = "parent", done = true),
                task("two", parentId = "parent"),
                task("three", parentId = "parent", done = true),
            ),
        )

        val progress = state.subtaskProgress("parent")!!
        assertEquals(2, progress.done)
        assertEquals(3, progress.total)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `parentOf finds the row a step belongs to, and nothing for a root task`() {
        val state = CadenceUiState(tasks = listOf(task("parent"), task("step", parentId = "parent")))

        assertEquals("parent", state.parentOf(state.tasks[1])?.id)
        assertNull(state.parentOf(state.tasks[0]))
    }

    @Test
    fun `expandedRows splices a parent's steps in directly below it`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("a"),
                task("b"),
                task("a-1", parentId = "a", sortOrder = 0),
                task("a-2", parentId = "a", sortOrder = 1),
                task("b-1", parentId = "b"),
            ),
        )

        val rows = state.expandedRows(state.rootTasks(), expandedIds = setOf("a"))

        assertEquals(listOf("a", "a-1", "a-2", "b"), rows.map { it.task.id })
        assertEquals(listOf(false, true, true, false), rows.map { it.isSubtaskRow })
    }

    // ── Projects ─────────────────────────────────────────────────────────────────────

    @Test
    fun `projectLabel reads the whole path, and nothing for an Inbox task`() {
        val state = CadenceUiState(
            tasks = listOf(task("filed", projectId = "child"), task("loose")),
            projects = listOf(project("root", "Home"), project("child", "Finance", parentId = "root")),
        )

        assertEquals("Home / Finance", state.projectLabel(state.tasks[0]))
        assertNull(state.projectLabel(state.tasks[1]))
    }

    @Test
    fun `tasksIn reaches the tasks of a project's subprojects too`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("direct", projectId = "root"),
                task("nested", projectId = "child"),
                task("elsewhere", projectId = "other"),
                task("nested-step", projectId = "child", parentId = "nested"),
            ),
            projects = listOf(project("root"), project("child", parentId = "root"), project("other")),
        )

        // Goes through rootTasks, so a step under a task in the project is not a row of its own.
        assertEquals(listOf("direct", "nested"), state.tasksIn("root").ids())
    }

    @Test
    fun `subprojects come back in order, and a grandchild is not one of them`() {
        val state = CadenceUiState(
            projects = listOf(
                project("root"),
                project("second", parentId = "root", sortOrder = 1),
                project("first", parentId = "root", sortOrder = 0),
            ),
        )

        assertEquals(listOf("first", "second"), state.subprojects("root").map { it.id })
    }

    // ── Sections ─────────────────────────────────────────────────────────────────────

    @Test
    fun `sectionsIn returns one project's bands, in drawing order`() {
        val state = CadenceUiState(
            sections = listOf(
                section("late", projectId = "root", sortOrder = 2),
                section("early", projectId = "root", sortOrder = 1),
                section("other", projectId = "elsewhere"),
            ),
        )

        assertEquals(listOf("early", "late"), state.sectionsIn("root").map { it.id })
    }

    @Test
    fun `a named band holds exactly the tasks filed under it`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("in-band", projectId = "root", sectionId = "band"),
                task("ungrouped", projectId = "root"),
            ),
            projects = listOf(project("root")),
            sections = listOf(section("band", projectId = "root")),
        )

        assertEquals(listOf("in-band"), state.tasksInSection("root", "band").ids())
    }

    @Test
    fun `the ungrouped band catches a subproject's own section, which this list never draws`() {
        // The deliberate rule: a project's screen also shows its subprojects' tasks, and one of
        // those may sit under a heading belonging to the *subproject*. Matching only nulls would
        // leave it in no band at all and drop it off the screen entirely.
        val state = CadenceUiState(
            tasks = listOf(
                task("plain", projectId = "root"),
                task("own-band", projectId = "root", sectionId = "root-band"),
                task("nested-band", projectId = "child", sectionId = "child-band"),
            ),
            projects = listOf(project("root"), project("child", parentId = "root")),
            sections = listOf(
                section("root-band", projectId = "root"),
                section("child-band", projectId = "child"),
            ),
        )

        assertEquals(listOf("plain", "nested-band"), state.tasksInSection("root", null).ids())
        assertEquals(listOf("own-band"), state.tasksInSection("root", "root-band").ids())
    }

    @Test
    fun `every task of a project lands in exactly one band`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("plain", projectId = "root"),
                task("own-band", projectId = "root", sectionId = "root-band"),
                task("nested-band", projectId = "child", sectionId = "child-band"),
                task("nested-plain", projectId = "child"),
            ),
            projects = listOf(project("root"), project("child", parentId = "root")),
            sections = listOf(
                section("root-band", projectId = "root"),
                section("child-band", projectId = "child"),
            ),
        )

        val drawn = state.sectionsIn("root").map { it.id }
        val banded = (drawn.map { state.tasksInSection("root", it) } + listOf(state.tasksInSection("root", null)))
            .flatten()

        assertEquals(state.tasksIn("root").ids().sorted(), banded.ids().sorted())
        assertEquals(banded.ids().size, banded.ids().toSet().size)
    }

    // ── Nesting ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a new project may nest under any top-level project`() {
        val state = CadenceUiState(
            projects = listOf(project("root"), project("child", parentId = "root"), project("other")),
        )

        assertEquals(listOf("root", "other"), state.nestingCandidates(exclude = null).map { it.id })
    }

    @Test
    fun `a childless project cannot nest under itself`() {
        val state = CadenceUiState(
            projects = listOf(project("root"), project("other")),
        )

        val candidates = state.nestingCandidates(exclude = state.projects[0]).map { it.id }

        assertEquals(listOf("other"), candidates)
    }

    @Test
    fun `a project that already has subprojects offers no nesting candidates`() {
        val state = CadenceUiState(
            projects = listOf(
                project("root"),
                project("child", parentId = "root"),
                project("other"),
            ),
        )

        val candidates = state.nestingCandidates(exclude = state.projects[0]).map { it.id }

        assertEquals(emptyList<String>(), candidates)
    }

    // ── Derived indexes ──────────────────────────────────────────────────────────────
    //
    // Every assertion above now runs against memoised maps rather than a scan per call. These two
    // pin the memoisation itself: that a repeated question is not re-derived, and that a new
    // state does not serve the previous one's answer.

    @Test
    fun `asking the same question twice does not derive it twice`() {
        val state = CadenceUiState(
            tasks = listOf(task("a", projectId = "root"), task("step", parentId = "a")),
            projects = listOf(project("root")),
            sections = listOf(section("band", projectId = "root")),
        )

        // Reference equality, not `assertEquals`: two equal lists would pass even if both were
        // rebuilt, which is the thing being ruled out.
        assertSame(state.rootTasks(), state.rootTasks())
        assertSame(state.tasksIn("root"), state.tasksIn("root"))
        assertSame(state.subtasks("a"), state.subtasks("a"))
        assertSame(state.sectionsIn("root"), state.sectionsIn("root"))
        assertSame(state.subprojects("root"), state.subprojects("root"))
    }

    @Test
    fun `a new state derives its own answer rather than the previous one's`() {
        val before = CadenceUiState(
            tasks = listOf(task("a", projectId = "root")),
            projects = listOf(project("root")),
        )

        val after = before.copy(tasks = before.tasks + task("b", projectId = "root"))

        assertEquals(listOf("a"), before.tasksIn("root").ids())
        assertEquals(listOf("a", "b"), after.tasksIn("root").ids())
    }

    // ── Tags ───────────────────────────────────────────────────────────────────────

    @Test
    fun `tagsOf returns the task's labels in the order it lists them`() {
        val state = CadenceUiState(
            tasks = listOf(task("a", tagIds = listOf("g2", "g1"))),
            tags = listOf(tag("g1"), tag("g2")),
        )

        assertEquals(listOf("g2", "g1"), state.tagsOf(state.tasks.single()).map { it.id })
    }

    /**
     * The read side of the packed column: deleting a tag writes one row and leaves the ids behind,
     * so this is where a link to a tag that is gone is repaired.
     */
    @Test
    fun `an id no live tag answers to is dropped rather than drawn`() {
        val state = CadenceUiState(
            tasks = listOf(task("a", tagIds = listOf("g1", "deleted"))),
            tags = listOf(tag("g1")),
        )

        assertEquals(listOf("g1"), state.tagsOf(state.tasks.single()).map { it.id })
    }

    /** Over every row, not just the roots: a labelled subtask is work wearing that label. */
    @Test
    fun `tasksWithTag finds subtasks as well as roots`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("parent", tagIds = listOf("g1")),
                task("step", parentId = "parent", tagIds = listOf("g1")),
                task("other"),
            ),
            tags = listOf(tag("g1")),
        )

        assertEquals(listOf("parent", "step"), state.tasksWithTag("g1").ids())
    }

    @Test
    fun `the sidebar count is open work only`() {
        val state = CadenceUiState(
            tasks = listOf(
                task("a", tagIds = listOf("g1")),
                task("b", tagIds = listOf("g1"), done = true),
            ),
            tags = listOf(tag("g1")),
        )

        assertEquals(2, state.tasksWithTag("g1").size)
        assertEquals(1, state.openCountForTag("g1"))
    }

    @Test
    fun `a tag nothing wears answers with an empty list rather than null`() {
        val state = CadenceUiState(tags = listOf(tag("g1")))

        assertEquals(emptyList<Task>(), state.tasksWithTag("g1"))
        assertEquals(0, state.openCountForTag("g1"))
        assertNull(state.tag("nope"))
    }

    // ── Attachments ──────────────────────────────────────────────────────────────────

    private fun file(id: String, taskId: String, sha256: String?, sortOrder: Int = 0) = Attachment(
        id = id,
        taskId = taskId,
        kind = AttachmentKind.FILE,
        name = id,
        mimeType = "application/pdf",
        sha256 = sha256,
        sortOrder = sortOrder,
    )

    @Test
    fun `a task's attachments come back in the order they were filed`() {
        val state = CadenceUiState(
            tasks = listOf(task("a"), task("b")),
            attachments = listOf(
                file("second", "a", "h2", sortOrder = 1),
                file("first", "a", "h1", sortOrder = 0),
                file("elsewhere", "b", "h3"),
            ),
        )

        assertEquals(listOf("first", "second"), state.attachmentsOf("a").map { it.id })
        assertEquals(2, state.attachmentCount("a"))
        assertEquals(0, state.attachmentCount("nobody"))
    }

    /**
     * The row is the truth of "the user attached this"; the blob is a cache that a restored
     * backup or a second device can legitimately leave empty. A missing one is a state to draw,
     * so it must be answerable without touching the filesystem.
     */
    @Test
    fun `a file is present only while its blob is, and a link always is`() {
        val link = Attachment(
            id = "link",
            taskId = "a",
            kind = AttachmentKind.LINK,
            name = "Receipt",
            mimeType = "text/uri-list",
            url = "https://example.org",
        )
        val here = file("here", "a", "h1")
        val gone = file("gone", "a", "h2")
        val state = CadenceUiState(
            tasks = listOf(task("a")),
            attachments = listOf(link, here, gone),
            presentBlobs = setOf("h1"),
        )

        assertTrue(state.isPresent(link))
        assertTrue(state.isPresent(here))
        assertFalse(state.isPresent(gone))
    }

    /** The index is built once per state, like every other derivation on this class. */
    @Test
    fun `attachmentsOf is computed once per state`() {
        val state = CadenceUiState(
            tasks = listOf(task("a")),
            attachments = listOf(file("one", "a", "h1")),
        )

        assertSame(state.attachmentsOf("a"), state.attachmentsOf("a"))
    }

    /** The index is built once per state, like every other derivation on this class. */
    @Test
    fun `tasksWithTag is computed once per state`() {
        val state = CadenceUiState(
            tasks = listOf(task("a", tagIds = listOf("g1"))),
            tags = listOf(tag("g1")),
        )

        assertSame(state.tasksWithTag("g1"), state.tasksWithTag("g1"))
    }
}
