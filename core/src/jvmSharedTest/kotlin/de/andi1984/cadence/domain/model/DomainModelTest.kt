package de.andi1984.cadence.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The logic tucked inside the otherwise-plain domain data classes: what a screen or a repository
 * reads straight off a [Task] or a [Project] rather than deriving itself. `:ui`'s
 * `CadenceUiStateTest` exercises the same rules indirectly through `CadenceUiState`; these pin the
 * functions themselves, in `:core`, where they live.
 */
class DomainModelTest {

    private fun task(
        id: String,
        due: LocalDate? = null,
        done: Boolean = false,
        spawnedFromId: String? = null,
    ) = Task(
        id = id,
        title = id,
        dueDate = due,
        completedAt = if (done) java.time.Instant.EPOCH else null,
        spawnedFromId = spawnedFromId,
    )

    // ── Task.isOverdue / isDueOn ────────────────────────────────────────────────────

    @Test
    fun `an open task with a due date in the past is overdue`() {
        val today = LocalDate.of(2026, 8, 20)
        assertTrue(task("t1", due = today.minusDays(1)).isOverdue(today))
    }

    @Test
    fun `a task due today is not overdue`() {
        val today = LocalDate.of(2026, 8, 20)
        assertFalse(task("t1", due = today).isOverdue(today))
    }

    @Test
    fun `a done task is never overdue, even with a due date in the past`() {
        val today = LocalDate.of(2026, 8, 20)
        assertFalse(task("t1", due = today.minusDays(5), done = true).isOverdue(today))
    }

    @Test
    fun `a task with no due date is never overdue`() {
        assertFalse(task("t1").isOverdue(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `isDueOn matches the exact date and nothing else`() {
        val due = LocalDate.of(2026, 8, 20)
        assertTrue(task("t1", due = due).isDueOn(due))
        assertFalse(task("t1", due = due).isDueOn(due.plusDays(1)))
        assertFalse(task("t1").isDueOn(due))
    }

    // ── withoutSupersededOccurrences ────────────────────────────────────────────────

    @Test
    fun `a completed occurrence that has been replaced is dropped`() {
        val tasks = listOf(
            task("done", done = true),
            task("next", spawnedFromId = "done"),
        )

        assertEquals(listOf("next"), tasks.withoutSupersededOccurrences().map { it.id })
    }

    @Test
    fun `the last occurrence in a chain stays even though it is completed`() {
        val tasks = listOf(task("only", done = true))

        assertEquals(listOf("only"), tasks.withoutSupersededOccurrences().map { it.id })
    }

    @Test
    fun `an open task is never dropped, superseded or not`() {
        val tasks = listOf(task("open", done = false))

        assertEquals(listOf("open"), tasks.withoutSupersededOccurrences().map { it.id })
    }

    // ── SubtaskProgress ──────────────────────────────────────────────────────────────

    @Test
    fun `subtask progress with no steps has a zero fraction and is never complete`() {
        val progress = SubtaskProgress(done = 0, total = 0)

        assertEquals(0f, progress.fraction, 0.0001f)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `subtask progress is complete only once every step is done`() {
        assertTrue(SubtaskProgress(done = 3, total = 3).isComplete)
        assertFalse(SubtaskProgress(done = 2, total = 3).isComplete)
        assertEquals(2f / 3f, SubtaskProgress(done = 2, total = 3).fraction, 0.0001f)
    }

    // ── Project.toTree ───────────────────────────────────────────────────────────────

    @Test
    fun `toTree groups subprojects under their parent, ordered by sortOrder`() {
        val projects = listOf(
            Project(id = "root", name = "Root", sortOrder = 0),
            Project(id = "child-b", name = "B", parentId = "root", sortOrder = 1),
            Project(id = "child-a", name = "A", parentId = "root", sortOrder = 0),
        )

        val tree = projects.toTree()

        assertEquals(listOf("root"), tree.map { it.project.id })
        assertEquals(listOf("child-a", "child-b"), tree.single().children.map { it.id })
    }

    @Test
    fun `toTree drops a subproject whose parent no longer exists`() {
        val projects = listOf(Project(id = "orphan", name = "Orphan", parentId = "ghost"))

        assertTrue(projects.toTree().isEmpty())
    }

    // ── projectPath ──────────────────────────────────────────────────────────────────

    @Test
    fun `projectPath is null for no project at all`() {
        assertNull(projectPath(null, emptyList()))
    }

    @Test
    fun `projectPath is just the name for a top-level project`() {
        val project = Project(id = "work", name = "Work")

        assertEquals("Work", projectPath(project, listOf(project)))
    }

    @Test
    fun `projectPath joins a subproject to its parent's name`() {
        val parent = Project(id = "work", name = "Work")
        val child = Project(id = "finance", name = "Finance", parentId = "work")

        assertEquals("Work / Finance", projectPath(child, listOf(parent, child)))
    }

    // ── Priority ─────────────────────────────────────────────────────────────────────

    @Test
    fun `filledBars runs from three down to zero as priority drops`() {
        assertEquals(3, Priority.P1.filledBars)
        assertEquals(2, Priority.P2.filledBars)
        assertEquals(1, Priority.P3.filledBars)
        assertEquals(0, Priority.P4.filledBars)
    }

    @Test
    fun `fromLevel resolves a known level and falls back to the default otherwise`() {
        assertEquals(Priority.P1, Priority.fromLevel(1))
        assertEquals(Priority.DEFAULT, Priority.fromLevel(99))
    }
}
