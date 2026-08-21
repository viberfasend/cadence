package de.andi1984.cadence.ui

import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.ui.dnd.DropIntent
import de.andi1984.cadence.ui.platform.BackupTarget
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The rest of the ViewModel's public surface, beside the undo state machine
 * [CadenceViewModelUndoTest] already covers: plain task/project/section CRUD, quick-add and
 * backup. Same arrangement as that file for the same reason — a real [CadenceRepository] over
 * hand-written fakes, `jvmTest` because constructing a real [CadenceSyncEngine] needs no Android
 * runtime that this compilation lacks.
 */
class CadenceViewModelCrudTest {

    private val taskStore = FakeTaskStore()
    private val projectStore = FakeProjectStore(taskStore)
    private val sectionStore = FakeSectionStore()
    private val repository = repositoryOver(taskStore, projectStore, sectionStore)
    private val reminders = RecordingReminderScheduler()
    private val settings = FakeSettingsStore()
    private val backupGateway = FakeBackupGateway()

    private fun TestScope.viewModel(): CadenceViewModel {
        val viewModel = CadenceViewModel(
            repository = repository,
            settingsStore = settings,
            reminderScheduler = reminders,
            backupGateway = backupGateway,
            syncEngine = CadenceSyncEngine(FakeSyncStore(), backgroundScope),
            scope = backgroundScope,
        )
        backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        return viewModel
    }

    private fun task(
        id: String,
        title: String = id,
        projectId: String? = null,
        due: LocalDate? = null,
    ) = Task(id = id, title = title, projectId = projectId, dueDate = due)

    private fun stored(id: String) = taskStore.allRows().first { it.id == id }

    // ── Tasks ────────────────────────────────────────────────────────────────────────

    @Test
    fun `toggling a task marks it done and toggling again reopens it`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()

        viewModel.toggleTask(task("t1"))
        runCurrent()
        assertTrue(stored("t1").isDone)

        viewModel.toggleTask(stored("t1"))
        runCurrent()
        assertTrue(!stored("t1").isDone)
    }

    @Test
    fun `saving a task writes the edit straight through`() = runTest {
        taskStore.seed(listOf(task("t1", title = "Old title")))
        val viewModel = viewModel()

        viewModel.saveTask(task("t1", title = "New title"))
        runCurrent()

        assertEquals("New title", stored("t1").title)
    }

    @Test
    fun `adding a subtask files it under the parent with the next sort order`() = runTest {
        // One existing step under the parent, so the new one lands at sortOrder 1.
        taskStore.seed(listOf(task("parent"), task("step0").copy(parentId = "parent", sortOrder = 0)))
        val viewModel = viewModel()

        viewModel.addSubtask(task("parent"), "  Buy milk  ")
        runCurrent()

        val added = taskStore.allRows().first { it.parentId == "parent" && it.title == "Buy milk" }
        assertEquals("parent", added.parentId)
        assertEquals(1, added.sortOrder)
        assertNull(viewModel.state.value.snackbarMessage)
    }

    @Test
    fun `adding a subtask with a blank title shows a validation snackbar and writes nothing`() = runTest {
        taskStore.seed(listOf(task("parent")))
        val viewModel = viewModel()

        viewModel.addSubtask(task("parent"), "   ")
        runCurrent()

        assertEquals(1, taskStore.allRows().size)
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `setPriority updates the task's priority`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()

        viewModel.setPriority(task("t1"), Priority.P1)
        runCurrent()

        assertEquals(Priority.P1, stored("t1").priority)
    }

    @Test
    fun `setDueDate updates the task's due date`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()
        val due = LocalDate.of(2026, 9, 1)

        viewModel.setDueDate(task("t1"), due)
        runCurrent()

        assertEquals(due, stored("t1").dueDate)
    }

    @Test
    fun `setDueTime updates the task's due time`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()

        viewModel.setDueTime(task("t1"), LocalTime.of(14, 30))
        runCurrent()

        assertEquals(LocalTime.of(14, 30), stored("t1").dueTime)
    }

    @Test
    fun `setReminder updates the task's reminder time`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()

        viewModel.setReminder(task("t1"), LocalTime.of(9, 0))
        runCurrent()

        assertEquals(LocalTime.of(9, 0), stored("t1").reminderTime)
    }

    @Test
    fun `setRecurrence updates the task's recurrence rule`() = runTest {
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()
        val rule = RecurrenceRule(interval = 2, unit = RecurrenceUnit.DAY)

        viewModel.setRecurrence(task("t1"), rule)
        runCurrent()

        assertEquals(rule, stored("t1").recurrence)
    }

    @Test
    fun `setProject moves a task to another project`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        taskStore.seed(listOf(task("t1")))
        val viewModel = viewModel()

        viewModel.setProject(task("t1"), "work")
        runCurrent()

        assertEquals("work", stored("t1").projectId)
    }

    @Test
    fun `setSection files a task under a section of its project`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        sectionStore.seed(listOf(Section(id = "s1", projectId = "work", name = "This week")))
        taskStore.seed(listOf(task("t1", projectId = "work")))
        val viewModel = viewModel()

        viewModel.setSection(task("t1", projectId = "work"), "s1")
        runCurrent()

        assertEquals("s1", stored("t1").sectionId)
    }

    @Test
    fun `snooze shifts the due date forward by the given number of days`() = runTest {
        taskStore.seed(listOf(task("t1", due = LocalDate.of(2999, 1, 1))))
        val viewModel = viewModel()

        viewModel.snooze(task("t1", due = LocalDate.of(2999, 1, 1)), days = 3)
        runCurrent()

        assertEquals(LocalDate.of(2999, 1, 4), stored("t1").dueDate)
    }

    @Test
    fun `rescheduleOverdue moves every overdue task to today`() = runTest {
        taskStore.seed(
            listOf(
                task("overdue", due = LocalDate.of(2000, 1, 1)),
                task("future", due = LocalDate.of(2999, 1, 1)),
                task("undated"),
            ),
        )
        val viewModel = viewModel()
        val today = LocalDate.now()

        viewModel.rescheduleOverdue()
        runCurrent()

        assertEquals(today, stored("overdue").dueDate)
        assertEquals(LocalDate.of(2999, 1, 1), stored("future").dueDate)
        assertNull(stored("undated").dueDate)
    }

    @Test
    fun `addParsedTask creates a task from the quick-add parse, falling back to the given project`() =
        runTest {
            val viewModel = viewModel()

            viewModel.addParsedTask(
                ParsedQuickAdd(title = "Water the plants", priority = Priority.P1),
                fallbackProjectId = "home",
            )
            runCurrent()

            val added = taskStore.allRows().single()
            assertEquals("Water the plants", added.title)
            assertEquals(Priority.P1, added.priority)
            assertEquals("home", added.projectId)
        }

    @Test
    fun `addParsedTask prefers the project the parse found over the fallback`() = runTest {
        val viewModel = viewModel()

        viewModel.addParsedTask(
            ParsedQuickAdd(title = "Pay rent", projectId = "finance"),
            fallbackProjectId = "home",
        )
        runCurrent()

        assertEquals("finance", taskStore.allRows().single().projectId)
    }

    @Test
    fun `addParsedTask does nothing for a blank title`() = runTest {
        val viewModel = viewModel()

        viewModel.addParsedTask(ParsedQuickAdd(title = "   "))
        runCurrent()

        assertTrue(taskStore.allRows().isEmpty())
    }

    // ── Projects ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addProject creates a project at the end of its siblings`() = runTest {
        projectStore.seed(listOf(Project(id = "p1", name = "Existing", sortOrder = 0)))
        val viewModel = viewModel()

        viewModel.addProject("New project", "#123456", null)
        runCurrent()

        val added = viewModel.state.value.projects.first { it.name == "New project" }
        assertEquals(1, added.sortOrder)
        assertEquals("#123456", added.colorHex)
    }

    @Test
    fun `addProject with a blank name shows a validation snackbar and creates nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.addProject("   ", "#123456", null)
        runCurrent()

        assertTrue(viewModel.state.value.projects.isEmpty())
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `editProject renames a project and recalculates its sort order when it moves to a new parent`() =
        runTest {
            projectStore.seed(
                listOf(
                    Project(id = "root", name = "Root"),
                    Project(id = "child-a", name = "A", parentId = "root", sortOrder = 0),
                    Project(id = "child-b", name = "B", parentId = "root", sortOrder = 1),
                    Project(id = "other-root", name = "Other root"),
                ),
            )
            val viewModel = viewModel()

            viewModel.editProject(
                Project(id = "child-b", name = "B", parentId = "root", sortOrder = 1),
                name = "Renamed",
                colorHex = "#654321",
                parentId = "other-root",
            )
            runCurrent()

            val moved = viewModel.state.value.projects.first { it.id == "child-b" }
            assertEquals("Renamed", moved.name)
            assertEquals("other-root", moved.parentId)
            // No sibling under "other-root" yet, so the moved project lands at order 0.
            assertEquals(0, moved.sortOrder)
        }

    @Test
    fun `editProject with a blank name shows a validation snackbar and writes nothing`() = runTest {
        val original = Project(id = "p1", name = "Original")
        projectStore.seed(listOf(original))
        val viewModel = viewModel()

        viewModel.editProject(original, name = " ", colorHex = "#000000", parentId = null)
        runCurrent()

        assertEquals("Original", viewModel.state.value.projects.single().name)
    }

    // ── Sections ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addSection appends a band to the end of a project's sections`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        sectionStore.seed(listOf(Section(id = "s1", projectId = "work", name = "Existing", sortOrder = 0)))
        val viewModel = viewModel()

        viewModel.addSection("work", "  New band  ")
        runCurrent()

        val added = viewModel.state.value.sections.first { it.name == "New band" }
        assertEquals(1, added.sortOrder)
    }

    @Test
    fun `addSection with a blank name shows a validation snackbar and creates nothing`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        val viewModel = viewModel()

        viewModel.addSection("work", "   ")
        runCurrent()

        assertTrue(viewModel.state.value.sections.isEmpty())
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `renameSection updates the section's name`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        sectionStore.seed(listOf(Section(id = "s1", projectId = "work", name = "Old name")))
        val viewModel = viewModel()

        viewModel.renameSection(Section(id = "s1", projectId = "work", name = "Old name"), "  New name  ")
        runCurrent()

        assertEquals("New name", viewModel.state.value.sections.single().name)
    }

    @Test
    fun `deleteSection removes the heading and leaves its tasks in the project`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        sectionStore.seed(listOf(Section(id = "s1", projectId = "work", name = "Band")))
        taskStore.seed(listOf(task("t1", projectId = "work").copy(sectionId = "s1")))
        val viewModel = viewModel()

        viewModel.deleteSection(Section(id = "s1", projectId = "work", name = "Band"))
        runCurrent()

        assertTrue(viewModel.state.value.sections.isEmpty())
        assertEquals("work", stored("t1").projectId)
        assertEquals("s1", stored("t1").sectionId)
    }

    // ── Backup ───────────────────────────────────────────────────────────────────────

    @Test
    fun `exportBackup records the gateway's outcome`() = runTest {
        val exportedAt = Instant.parse("2026-08-11T09:00:00Z")
        backupGateway.exported = BackupOutcome.Exported(projects = 2, tasks = 5, exportedAt = exportedAt)
        val viewModel = viewModel()

        viewModel.exportBackup(BackupTarget("file:///backup.json"))
        runCurrent()

        assertEquals(backupGateway.exported, viewModel.state.value.backupOutcome)
    }

    @Test
    fun `importBackup merges the outcomes of several files into one`() = runTest {
        backupGateway.imports = listOf(
            BackupOutcome.Imported(projects = 1, tasks = 2, files = 1),
            BackupOutcome.Imported(projects = 3, tasks = 4, files = 1),
        )
        val viewModel = viewModel()

        viewModel.importBackup(listOf(BackupTarget("a"), BackupTarget("b")))
        runCurrent()

        val outcome = viewModel.state.value.backupOutcome as BackupOutcome.Imported
        assertEquals(4, outcome.projects)
        assertEquals(6, outcome.tasks)
        assertEquals(2, outcome.files)
        assertEquals(0, outcome.unreadableFiles)
    }

    @Test
    fun `importBackup with only unreadable files reports the first failure`() = runTest {
        backupGateway.imports = listOf(BackupOutcome.Failed(BackupFailure.NOT_JSON))
        val viewModel = viewModel()

        viewModel.importBackup(listOf(BackupTarget("bad")))
        runCurrent()

        assertEquals(BackupOutcome.Failed(BackupFailure.NOT_JSON), viewModel.state.value.backupOutcome)
    }

    @Test
    fun `clearBackupOutcome clears the last outcome`() = runTest {
        backupGateway.exported = BackupOutcome.Exported(projects = 0, tasks = 0, exportedAt = Instant.EPOCH)
        val viewModel = viewModel()
        viewModel.exportBackup(BackupTarget("file:///backup.json"))
        runCurrent()

        viewModel.clearBackupOutcome()
        runCurrent()

        assertNull(viewModel.state.value.backupOutcome)
    }

    // ── Duplicating ──────────────────────────────────────────────────────────────────

    @Test
    fun `duplicating a task copies its checklist, unticked, as new work`() = runTest {
        taskStore.seed(
            listOf(
                task("t1", title = "Ship it").copy(
                    priority = Priority.P1,
                    completedAt = Instant.EPOCH,
                    spawnedFromId = "older-occurrence",
                ),
                task("s1", title = "Write the notes").copy(
                    parentId = "t1",
                    completedAt = Instant.EPOCH,
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.duplicateTask(stored("t1"), "Ship it (copy)")
        runCurrent()

        val copy = taskStore.allRows().single { it.title == "Ship it (copy)" }
        assertEquals(Priority.P1, copy.priority)
        // New work: not finished, and not part of anyone's recurrence chain — a copy that claimed
        // to replace an occurrence would take the original's place in every undated list.
        assertNull(copy.completedAt)
        assertNull(copy.spawnedFromId)
        val copiedStep = taskStore.allRows().single { it.parentId == copy.id }
        assertEquals("Write the notes", copiedStep.title)
        assertNull(copiedStep.completedAt)
    }

    // ── Drops ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a drop that moves a task into another project's band does both writes`() = runTest {
        taskStore.seed(listOf(task("t1", projectId = "home")))
        projectStore.seed(listOf(Project(id = "home", name = "Home"), Project(id = "work", name = "Work")))
        sectionStore.seed(listOf(Section(id = "band", projectId = "work", name = "Band")))
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.MoveTask("t1", "work", "band"))
        runCurrent()

        // The section is written against the row `moveToProject` left behind, not against the
        // snapshot the drag started from — which still named `home` and would have been refused.
        assertEquals("work", stored("t1").projectId)
        assertEquals("band", stored("t1").sectionId)
    }

    @Test
    fun `a drop that reorders writes the order it was handed`() = runTest {
        taskStore.seed(
            listOf(
                task("a").copy(sortOrder = 0),
                task("b").copy(sortOrder = 1),
                task("c").copy(sortOrder = 2),
            ),
        )
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.ReorderTasks(listOf("c", "a", "b")))
        runCurrent()

        assertEquals(0, stored("c").sortOrder)
        assertEquals(1, stored("a").sortOrder)
        assertEquals(2, stored("b").sortOrder)
    }

    @Test
    fun `a rejected drop writes nothing`() = runTest {
        taskStore.seed(listOf(task("t1", projectId = "home")))
        val viewModel = viewModel()
        val before = stored("t1")

        viewModel.applyDropIntent(DropIntent.Rejected)
        runCurrent()

        assertEquals(before, stored("t1"))
    }

    @Test
    fun `a drop that nests a project keeps its name and colour`() = runTest {
        projectStore.seed(
            listOf(
                Project(id = "home", name = "Home", colorHex = "#123456"),
                Project(id = "work", name = "Work"),
            ),
        )
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.NestProject("home", "work"))
        runCurrent()

        val nested = viewModel.state.value.project("home")!!
        assertEquals("work", nested.parentId)
        assertEquals("Home", nested.name)
        assertEquals("#123456", nested.colorHex)
    }
}
