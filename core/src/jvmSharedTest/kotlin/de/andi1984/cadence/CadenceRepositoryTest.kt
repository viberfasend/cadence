package de.andi1984.cadence

import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.RepositoryResult
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Completion is where recurrence turns into rows, so this is where a duplicate can be created.
 * The rows the UI hands back are snapshots, and the same open snapshot arrives again on a second
 * tap — every test here works with a deliberately stale [Task].
 */
class CadenceRepositoryTest {

    private val fortnightly = RecurrenceRule(interval = 2, unit = RecurrenceUnit.WEEK)
    private val today = LocalDate.of(2026, 8, 7)

    private val taskStore = FakeTaskStore()
    private val repository = CadenceRepository(taskStore, FakeProjectStore(), FakeBackupStore())

    private fun store(task: Task): Task = taskStore.row(taskStore.put(task))

    private fun rowsTitled(title: String) = taskStore.rows().filter { it.title == title }

    /** The task from the bug report: fortnightly, in the Inbox, due today. */
    private fun recurring(
        due: LocalDate? = LocalDate.of(2026, 8, 7),
        priority: Priority = Priority.DEFAULT,
    ) = Task(title = "Rat poison", priority = priority, dueDate = due, recurrence = fortnightly)

    @Test
    fun `completing a recurring task leaves the finished row and one successor`() = runTest {
        val task = store(recurring())

        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Rat poison")
        assertEquals(2, rows.size)
        val done = rows.single { it.isDone }
        val next = rows.single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 7), done.dueDate)
        assertEquals(LocalDate.of(2026, 8, 21), next.dueDate)
        assertEquals(fortnightly, next.recurrence)
    }

    @Test
    fun `a second tap on the same open snapshot does not insert a second successor`() = runTest {
        val task = store(recurring())

        // Both calls carry the row as it was drawn — open — because the list has not
        // re-composed between the two taps.
        repository.setCompleted(task, true, today)
        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Rat poison")
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { !it.isDone })
    }

    @Test
    fun `completing a task with no recurrence twice only closes it`() = runTest {
        val task = store(Task(title = "Call the vet"))

        repository.setCompleted(task, true, today)
        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Call the vet")
        assertEquals(1, rows.size)
        assertTrue(rows.single().isDone)
    }

    @Test
    fun `the completion time of a closed task is not overwritten by a later tap`() = runTest {
        val task = store(recurring(due = null))
        repository.setCompleted(task, true, today)
        val closedAt = rowsTitled("Rat poison").single { it.completedAt != null }.completedAt

        repository.setCompleted(task, true, today)

        val stillClosedAt = rowsTitled("Rat poison").single { it.completedAt != null }.completedAt
        assertEquals(closedAt, stillClosedAt)
    }

    @Test
    fun `the successor inherits edits made after the row was drawn`() = runTest {
        val drawn = store(recurring(due = LocalDate.of(2026, 8, 1), priority = Priority.P3))
        // The detail screen moved the task while the list still showed the old row.
        val edited = drawn.copy(priority = Priority.P1, dueDate = LocalDate.of(2026, 8, 5))
        taskStore.put(edited)

        repository.setCompleted(drawn, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 19), next.dueDate)
        assertEquals(Priority.P1, next.priority)
    }

    @Test
    fun `a checklist is handed over once, unticked and shifted`() = runTest {
        val parent = store(recurring())
        store(
            Task(
                title = "Check the traps",
                parentId = parent.id,
                dueDate = LocalDate.of(2026, 8, 6),
            ),
        )

        repository.setCompleted(parent, true, today)
        repository.setCompleted(parent, true, today)

        val steps = rowsTitled("Check the traps")
        assertEquals(2, steps.size)
        val handedOver = steps.single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 20), handedOver.dueDate)
        // The step went with the new occurrence, not with the one that was just finished.
        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(next.id, handedOver.parentId)
        assertTrue(steps.single { it.isDone }.parentId == parent.id)
    }

    @Test
    fun `adding a subtask to an ordinary top-level task succeeds`() = runTest {
        val parent = store(Task(title = "Plan the trip"))

        val result = repository.addSubtask(parent, "Book flights")

        assertTrue(result is RepositoryResult.Success)
        val step = rowsTitled("Book flights").single()
        assertEquals(parent.id, step.parentId)
    }

    @Test
    fun `adding a subtask while viewing a subtask files it under the same parent`() = runTest {
        val parent = store(Task(title = "Plan the trip"))
        val firstStep = store(Task(title = "Book flights", parentId = parent.id))

        val result = repository.addSubtask(firstStep, "Book hotel")

        assertTrue(result is RepositoryResult.Success)
        val secondStep = rowsTitled("Book hotel").single()
        assertEquals(parent.id, secondStep.parentId)
    }

    @Test
    fun `the successor records which occurrence it replaces`() = runTest {
        val task = store(recurring())

        repository.setCompleted(task, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(task.id, next.spawnedFromId)
    }

    @Test
    fun `reopening a recurring task takes the occurrence it created with it`() = runTest {
        val task = store(recurring())
        repository.setCompleted(task, true, today)
        val next = rowsTitled("Rat poison").single { it.completedAt == null }

        val removed = repository.setCompleted(task, false, today)

        assertEquals(listOf(next.id), removed)
        val rows = rowsTitled("Rat poison")
        assertEquals(1, rows.size)
        assertEquals(task.id, rows.single().id)
        assertNull(rows.single().completedAt)
    }

    @Test
    fun `reopening leaves a successor that has itself been ticked off`() = runTest {
        val first = store(recurring())
        repository.setCompleted(first, true, today)
        val second = rowsTitled("Rat poison").single { it.completedAt == null }
        // The chain moved on: the second occurrence is done and has a successor of its own.
        repository.setCompleted(second, true, LocalDate.of(2026, 8, 21))

        val removed = repository.setCompleted(first, false, today)

        assertTrue(removed.isEmpty())
        assertEquals(3, rowsTitled("Rat poison").size)
    }

    @Test
    fun `reopening a recurring task also removes the successor's checklist`() = runTest {
        val parent = store(recurring())
        store(Task(title = "Check the traps", parentId = parent.id))
        repository.setCompleted(parent, true, today)

        repository.setCompleted(parent, false, today)

        // The handed-over copy is gone with its occurrence; the original step stays put.
        val steps = rowsTitled("Check the traps")
        assertEquals(1, steps.size)
        assertEquals(parent.id, steps.single().parentId)
    }

    @Test
    fun `reopening clears the completion and reopening again is a no-op`() = runTest {
        val task = store(Task(title = "Call the vet"))
        repository.setCompleted(task, true, today)
        val done = rowsTitled("Call the vet").single()

        repository.setCompleted(done, false, today)
        repository.setCompleted(done, false, today)

        val rows = rowsTitled("Call the vet")
        assertEquals(1, rows.size)
        assertNull(rows.single().completedAt)
        assertFalse(rows.single().isDone)
    }
}

/** An in-memory [TaskStore] with SQLite's semantics for the guarded writes. */
private class FakeTaskStore : TaskStore {

    private val table = MutableStateFlow<Map<String, Task>>(emptyMap())
    private var nextId = 1

    /**
     * Test-only setup helper: mints an id when [task] does not carry one, the way
     * [de.andi1984.cadence.data.CadenceRepository] does for a real insert — [TaskStore.insert]
     * itself now only ever receives a task that already has its final id.
     */
    fun put(task: Task): String {
        val id = task.id.ifBlank { "fake-task-${nextId++}" }
        table.value = table.value + (id to task.copy(id = id))
        return id
    }

    fun row(id: String): Task = table.value[id] ?: error("no task with id $id")

    fun rows(): List<Task> = table.value.values.toList()

    override fun observeAll(): Flow<List<Task>> = table.map { it.values.toList() }

    override fun observeById(id: String): Flow<Task?> = table.map { it[id] }

    override suspend fun getAll(): List<Task> = rows()

    override suspend fun byId(id: String): Task? = table.value[id]

    override suspend fun subtasksOf(parentId: String): List<Task> = rows()
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(task: Task) {
        put(task)
    }

    override suspend fun update(task: Task) {
        if (table.value.containsKey(task.id)) table.value = table.value + (task.id to task)
    }

    override suspend fun deleteWithSubtasks(id: String) {
        table.value = table.value.filterValues { it.id != id && it.parentId != id }
    }

    override suspend fun completeIfOpen(id: String, completedAt: Instant): Int {
        val task = table.value[id] ?: return 0
        if (task.completedAt != null) return 0
        table.value = table.value + (id to task.copy(completedAt = completedAt, updatedAt = completedAt))
        return 1
    }

    override suspend fun reopenIfDone(id: String, updatedAt: Instant): Int {
        val task = table.value[id] ?: return 0
        if (task.completedAt == null) return 0
        table.value = table.value + (id to task.copy(completedAt = null, updatedAt = updatedAt))
        return 1
    }

    override suspend fun openSuccessorsOf(id: String): List<String> = rows()
        .filter { it.spawnedFromId == id && it.completedAt == null }
        .map { it.id }
}

/** Projects play no part in completing a task; these fakes only satisfy the constructor. */
private class FakeProjectStore : ProjectStore {

    override fun observeAll(): Flow<List<Project>> = MutableStateFlow(emptyList())

    override suspend fun getAll(): List<Project> = emptyList()

    override suspend fun insert(project: Project) = Unit

    override suspend fun update(project: Project) = Unit

    override suspend fun taskIdsIn(id: String): List<String> = emptyList()

    override suspend fun deleteWithChildren(id: String, deleteTasks: Boolean) = Unit
}

private class FakeBackupStore : BackupStore {

    override suspend fun replaceAll(projects: List<Project>, tasks: List<Task>) = Unit
}
