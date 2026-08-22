package de.andi1984.cadence.ui

import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The undo state machine: `offerUndo` → `pendingDeleteIds` → `commitPendingDelete`.
 *
 * The whole design is that a destructive write does not reach the database until the window
 * elapses, so every test here is about *when* something happened as much as *whether* it did.
 * Time is virtual — `StandardTestDispatcher` sharing `runTest`'s scheduler — so the five-second
 * window costs nothing and "before" and "after" are exact rather than racy.
 *
 * The ViewModel runs on `runTest`'s `backgroundScope` rather than on the test coroutine itself:
 * its `init` starts collectors that never finish, and `runTest` waits for its own children
 * before it returns. `backgroundScope` is the scheduler-sharing scope that is exempt from that
 * wait and is cancelled for us — which also stands in for the `close()` the shell would call.
 *
 * **`jvmTest`, not `jvmSharedTest`**, unlike the two state tests beside it. The ViewModel takes a
 * concrete `CadenceSyncEngine`, and constructing one builds a real supabase-kt client — harmless
 * here, since signed out it makes no request at all, but the Android unit-test JVM has no Android
 * runtime behind it for that library to find. The rules under test are the ViewModel's own and
 * are identical on both targets, so running them on the desktop compilation is the whole value.
 */
class CadenceViewModelUndoTest {

    private val taskStore = FakeTaskStore()
    private val projectStore = FakeProjectStore(taskStore)
    private val sectionStore = FakeSectionStore()
    private val repository = repositoryOver(taskStore, projectStore, sectionStore)
    private val reminders = RecordingReminderScheduler()
    private val settings = FakeSettingsStore()

    /**
     * Builds the ViewModel on `runTest`'s [TestScope.backgroundScope] and subscribes to `state`,
     * because `stateIn(WhileSubscribed)` keeps the upstream cold until something reads it — and
     * every deferred delete reads `state.value` for the rows it is about to hide.
     *
     * `backgroundScope` rather than a scope of our own: it already runs on the test's scheduler,
     * it is cancelled before `runTest` drains that scheduler, and work in it therefore cannot
     * hold the drain open. That last part is what a scope of our own gets wrong — see the note
     * on `:app-desktop`'s `DesktopReminderSchedulerTest`, where one `delay` loop outside the
     * test's own scope hung the whole task at 100% CPU with nothing to show for it.
     */
    private fun TestScope.viewModel(): CadenceViewModel {
        val viewModel = CadenceViewModel(
            repository = repository,
            settingsStore = settings,
            reminderScheduler = reminders,
            backupGateway = FakeBackupGateway(),
            attachmentOpener = RecordingAttachmentOpener(),
            syncEngine = CadenceSyncEngine(FakeSyncStore(), backgroundScope),
            scope = backgroundScope,
        )
        backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        return viewModel
    }

    private fun task(
        id: String,
        projectId: String? = null,
        parentId: String? = null,
        due: LocalDate? = null,
    ) = Task(id = id, title = id, projectId = projectId, parentId = parentId, dueDate = due)

    private fun liveTaskIds() = taskStore.allRows().filter { it.deletedAt == null }.map { it.id }

    // ── Deleting a task ──────────────────────────────────────────────────────────────

    @Test
    fun `a deleted task disappears at once but is not written until the window elapses`() = runTest {
        taskStore.seed(listOf(task("parent"), task("step", parentId = "parent"), task("other")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("parent"))
        runCurrent()

        // Hidden immediately — the row and its steps — while the database still holds them.
        assertEquals(setOf("parent", "step"), viewModel.state.value.pendingDeleteIds)
        assertEquals(listOf("other"), viewModel.state.value.tasks.map { it.id })
        assertEquals(listOf("parent", "step", "other"), liveTaskIds())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(listOf("other"), liveTaskIds())
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    @Test
    fun `undo inside the window writes nothing at all and brings the rows straight back`() = runTest {
        taskStore.seed(listOf(task("parent"), task("step", parentId = "parent")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("parent"))
        runCurrent()
        viewModel.undo()
        runCurrent()

        // Past the window the cancelled job would have fired at, to prove it is really gone.
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() * 2)
        runCurrent()

        assertEquals(listOf("parent", "step"), liveTaskIds())
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
        assertNull(viewModel.state.value.snackbarMessage)
        assertEquals(emptyList<String>(), reminders.cancelled)
    }

    @Test
    fun `committing a task delete cancels the alarms of the task and every step under it`() = runTest {
        taskStore.seed(
            listOf(
                task("parent", due = LocalDate.of(2026, 8, 17)),
                task("step", parentId = "parent", due = LocalDate.of(2026, 8, 18)),
            ),
        )
        val viewModel = viewModel()

        viewModel.deleteTask(task("parent"))
        runCurrent()
        assertEquals(emptyList<String>(), reminders.cancelled)

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("parent", "step"), reminders.cancelled.toSet())
    }

    @Test
    fun `the snackbar carries the undo action and counts the rows it will take`() = runTest {
        taskStore.seed(listOf(task("parent"), task("a", parentId = "parent"), task("b", parentId = "parent")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("parent"))
        runCurrent()

        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Counted
        assertEquals(3, message.count)
        val action = message.undoAction as UndoAction.DeleteTask
        assertEquals(setOf("parent", "a", "b"), action.ids)
    }

    // ── A second delete settles the first ────────────────────────────────────────────

    @Test
    fun `a second delete commits the first one out of band rather than racing it`() = runTest {
        taskStore.seed(listOf(task("first"), task("second"), task("kept")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("first"))
        runCurrent()
        // Well inside the first window: nothing has been written yet.
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() / 2)
        assertEquals(listOf("first", "second", "kept"), liveTaskIds())

        viewModel.deleteTask(task("second"))
        runCurrent()

        // The user moved on, so the first delete is settled now — without waiting out its window.
        assertEquals(listOf("second", "kept"), liveTaskIds())
        assertEquals(setOf("second"), viewModel.state.value.pendingDeleteIds)

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(listOf("kept"), liveTaskIds())
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    @Test
    fun `undo after a second delete only rescues the second — the first is already written`() = runTest {
        taskStore.seed(listOf(task("first"), task("second")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("first"))
        runCurrent()
        viewModel.deleteTask(task("second"))
        runCurrent()
        viewModel.undo()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() * 2)
        runCurrent()

        assertEquals(listOf("second"), liveTaskIds())
    }

    // ── Deleting a project ───────────────────────────────────────────────────────────

    @Test
    fun `deleting a project cancels the reminder of a subtask filed under it`() = runTest {
        // The regression this test exists for (#101): `tasksIn` goes through `rootTasks()`, so a
        // *subtask* of a task in the project is not in the list the UI can see — and an alarm
        // outlives the row unless something cancels it. The repository is the one that knows the
        // whole set, which is exactly why `deleteProject` returns it.
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        taskStore.seed(
            listOf(
                task("parent", projectId = "work", due = LocalDate.of(2026, 8, 17)),
                task("step", projectId = "work", parentId = "parent", due = LocalDate.of(2026, 8, 18)),
            ),
        )
        val viewModel = viewModel()

        viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = true)
        runCurrent()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("parent", "step"), reminders.cancelled.toSet())
        assertEquals(emptyList<String>(), liveTaskIds())
    }

    @Test
    fun `a deleted project hides its subtasks for the undo window too`() = runTest {
        // Same cause as above: the ids that hide rows and the ids that cancel alarms want the
        // same source. Today and Upcoming do not filter subtasks out, so a dated step whose
        // project is being deleted would otherwise stay on screen and then blink away.
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        taskStore.seed(
            listOf(
                task("parent", projectId = "work"),
                task("step", projectId = "work", parentId = "parent"),
                task("elsewhere"),
            ),
        )
        val viewModel = viewModel()

        viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = true)
        runCurrent()

        assertTrue("step" in viewModel.state.value.pendingDeleteIds)
        assertEquals(listOf("elsewhere"), viewModel.state.value.tasks.map { it.id })
    }

    @Test
    fun `deleting a project without its tasks hides only the project, and files them in the Inbox`() =
        runTest {
            projectStore.seed(listOf(Project(id = "work", name = "Work")))
            taskStore.seed(listOf(task("kept", projectId = "work")))
            val viewModel = viewModel()

            viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = false)
            runCurrent()

            assertEquals(setOf("work"), viewModel.state.value.pendingDeleteIds)
            assertEquals(listOf("kept"), viewModel.state.value.tasks.map { it.id })

            advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
            runCurrent()

            assertEquals(listOf("kept"), liveTaskIds())
            assertNull(taskStore.allRows().first { it.id == "kept" }.projectId)
            assertEquals(emptyList<String>(), reminders.cancelled)
        }

    @Test
    fun `undoing a project delete leaves the project and its tasks exactly where they were`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        taskStore.seed(listOf(task("kept", projectId = "work")))
        val viewModel = viewModel()

        viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = true)
        runCurrent()
        viewModel.undo()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() * 2)
        runCurrent()

        assertEquals(listOf("kept"), liveTaskIds())
        assertEquals(listOf("work"), viewModel.state.value.projects.map { it.id })
        assertEquals("work", taskStore.allRows().first { it.id == "kept" }.projectId)
    }

    // ── The danger zone ──────────────────────────────────────────────────────────────

    @Test
    fun `wiping everything empties the lists at once and tombstones after the window`() = runTest {
        projectStore.seed(listOf(Project(id = "work", name = "Work")))
        taskStore.seed(listOf(task("a", projectId = "work"), task("b")))
        val viewModel = viewModel()

        viewModel.wipeEverything()
        runCurrent()

        assertEquals(emptyList<String>(), viewModel.state.value.tasks.map { it.id })
        assertEquals(emptyList<String>(), viewModel.state.value.projects.map { it.id })
        assertEquals(listOf("a", "b"), liveTaskIds())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(emptyList<String>(), liveTaskIds())
        assertEquals(setOf("a", "b"), reminders.cancelled.toSet())
    }

    @Test
    fun `wiping an empty app offers no undo at all`() = runTest {
        val viewModel = viewModel()

        viewModel.wipeEverything()
        runCurrent()

        assertNull(viewModel.state.value.snackbarMessage)
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    // ── Reminders reconcile on every emission ────────────────────────────────────────

    @Test
    fun `switching reminders off hands the scheduler the same tasks with their times stripped`() =
        runTest {
            taskStore.seed(
                listOf(
                    Task(
                        id = "a",
                        title = "a",
                        dueDate = LocalDate.of(2026, 8, 17),
                        reminderTime = LocalTime.of(9, 0),
                    ),
                ),
            )
            val viewModel = viewModel()

            assertEquals(listOf(LocalTime.of(9, 0)), reminders.lastSynced.map { it.reminderTime })

            viewModel.setRemindersEnabled(false)
            runCurrent()

            // The same one task, so the scheduler cancels what it had; an empty list would leave
            // the alarm standing.
            assertEquals(1, reminders.lastSynced.size)
            assertEquals(listOf<LocalTime?>(null), reminders.lastSynced.map { it.reminderTime })
        }

    @Test
    fun `dismissing the snackbar does not rush the write`() = runTest {
        taskStore.seed(listOf(task("doomed")))
        val viewModel = viewModel()

        viewModel.deleteTask(task("doomed"))
        runCurrent()
        viewModel.dismissSnackbar()
        runCurrent()

        assertNull(viewModel.state.value.snackbarMessage)
        assertEquals(listOf("doomed"), liveTaskIds())
        assertFalse(viewModel.state.value.pendingDeleteIds.isEmpty())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(emptyList<String>(), liveTaskIds())
    }
}
