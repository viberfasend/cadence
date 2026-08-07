package de.andi1984.cadence

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.db.BackupDao
import de.andi1984.cadence.data.db.ProjectDao
import de.andi1984.cadence.data.db.ProjectEntity
import de.andi1984.cadence.data.db.TaskDao
import de.andi1984.cadence.data.db.TaskEntity
import de.andi1984.cadence.data.db.toDomain
import de.andi1984.cadence.data.db.toEntity
import de.andi1984.cadence.domain.model.Priority
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
import java.time.LocalDate

/**
 * Completion is where recurrence turns into rows, so this is where a duplicate can be created.
 * The rows the UI hands back are snapshots, and the same open snapshot arrives again on a second
 * tap — every test here works with a deliberately stale [Task].
 */
class CadenceRepositoryTest {

    private val fortnightly = RecurrenceRule(interval = 2, unit = RecurrenceUnit.WEEK)
    private val today = LocalDate.of(2026, 8, 7)

    private val taskDao = FakeTaskDao()
    private val repository = CadenceRepository(taskDao, FakeProjectDao(), FakeBackupDao())

    private fun store(task: Task): Task {
        val id = taskDao.put(task.toEntity())
        return taskDao.row(id).toDomain()
    }

    private fun rowsTitled(title: String) = taskDao.rows().filter { it.title == title }

    /** The task from the bug report: fortnightly, in the Inbox, due today. */
    private fun recurring(
        due: LocalDate? = LocalDate.of(2026, 8, 7),
        priority: Priority = Priority.DEFAULT,
    ) = Task(title = "Rat poison", priority = priority, dueDate = due, recurrence = fortnightly)

    @Test
    fun `completing a recurring task leaves the finished row and one successor`() = runTest {
        val task = store(recurring())

        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Rat poison").map { it.toDomain() }
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

        val rows = rowsTitled("Rat poison").map { it.toDomain() }
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
        assertTrue(rows.single().toDomain().isDone)
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
        taskDao.put(edited.toEntity())

        repository.setCompleted(drawn, true, today)

        val next = rowsTitled("Rat poison").map { it.toDomain() }.single { !it.isDone }
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

        val steps = rowsTitled("Check the traps").map { it.toDomain() }
        assertEquals(2, steps.size)
        val handedOver = steps.single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 20), handedOver.dueDate)
        // The step went with the new occurrence, not with the one that was just finished.
        val next = rowsTitled("Rat poison").map { it.toDomain() }.single { !it.isDone }
        assertEquals(next.id, handedOver.parentId)
        assertTrue(steps.single { it.isDone }.parentId == parent.id)
    }

    @Test
    fun `reopening clears the completion and reopening again is a no-op`() = runTest {
        val task = store(Task(title = "Call the vet"))
        repository.setCompleted(task, true, today)
        val done = rowsTitled("Call the vet").single().toDomain()

        repository.setCompleted(done, false, today)
        repository.setCompleted(done, false, today)

        val rows = rowsTitled("Call the vet")
        assertEquals(1, rows.size)
        assertNull(rows.single().completedAt)
        assertFalse(rows.single().toDomain().isDone)
    }
}

/** An in-memory stand-in for the generated DAO, with SQLite's semantics for the guarded writes. */
private class FakeTaskDao : TaskDao {

    private val table = MutableStateFlow<Map<Long, TaskEntity>>(emptyMap())
    private var nextId = 1L

    /** Inserts or replaces, the way `OnConflictStrategy.REPLACE` does. */
    fun put(task: TaskEntity): Long {
        val id = if (task.id == 0L) nextId++ else task.id
        table.value = table.value + (id to task.copy(id = id))
        return id
    }

    fun row(id: Long): TaskEntity = table.value[id] ?: error("no task with id $id")

    fun rows(): List<TaskEntity> = table.value.values.toList()

    override fun observeAll(): Flow<List<TaskEntity>> = table.map { it.values.toList() }

    override fun observeById(id: Long): Flow<TaskEntity?> = table.map { it[id] }

    override suspend fun getAll(): List<TaskEntity> = rows()

    override suspend fun byId(id: Long): TaskEntity? = table.value[id]

    override suspend fun subtasksOf(parentId: Long): List<TaskEntity> = rows()
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(task: TaskEntity): Long = put(task)

    override suspend fun update(task: TaskEntity) {
        if (table.value.containsKey(task.id)) table.value = table.value + (task.id to task)
    }

    override suspend fun delete(task: TaskEntity) {
        table.value = table.value - task.id
    }

    override suspend fun deleteWithSubtasks(id: Long) {
        table.value = table.value.filterValues { it.id != id && it.parentId != id }
    }

    override suspend fun completeIfOpen(id: Long, completedAt: Long): Int {
        val task = table.value[id] ?: return 0
        if (task.completedAt != null) return 0
        table.value = table.value + (id to task.copy(completedAt = completedAt))
        return 1
    }

    override suspend fun reopenIfDone(id: Long): Int {
        val task = table.value[id] ?: return 0
        if (task.completedAt == null) return 0
        table.value = table.value + (id to task.copy(completedAt = null))
        return 1
    }
}

/** Projects play no part in completing a task; these fakes only satisfy the constructor. */
private class FakeProjectDao : ProjectDao() {

    override fun observeAll(): Flow<List<ProjectEntity>> = MutableStateFlow(emptyList())

    override suspend fun getAll(): List<ProjectEntity> = emptyList()

    override suspend fun insert(project: ProjectEntity): Long = project.id

    override suspend fun update(project: ProjectEntity) = Unit

    override suspend fun taskIdsIn(id: Long): List<Long> = emptyList()

    override suspend fun moveTasksToInbox(id: Long) = Unit

    override suspend fun deleteTasksIn(id: Long) = Unit

    override suspend fun deleteRows(id: Long) = Unit
}

private class FakeBackupDao : BackupDao() {

    override suspend fun deleteAllTasks() = Unit

    override suspend fun deleteAllProjects() = Unit

    override suspend fun insertProjects(projects: List<ProjectEntity>) = Unit

    override suspend fun insertTasks(tasks: List<TaskEntity>) = Unit
}
