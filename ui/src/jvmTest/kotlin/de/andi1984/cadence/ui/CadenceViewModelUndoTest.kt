package de.andi1984.cadence.ui

import de.andi1984.cadence.data.StoreTransaction
import de.andi1984.cadence.data.TransactionScope
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Instant

/**
 * The undo state machine as `CadenceViewModel` wires it up: `deleteTask`/`deleteProject`/
 * `wipeEverything` → `de.andi1984.cadence.ui.undo.UndoSlot` → [CadenceViewModel.commitPendingDelete].
 *
 * `UndoSlot`'s own timing rules — *when* an offer commits, the two #114 rules about a second
 * delete and about the snackbar being one slot — are pinned in `UndoSlotTest` against a recording
 * `commit` lambda, with no repository at all. What is left here is what only the real wiring can
 * prove: that the ids a delete captures are the *right* ids (subtasks, a project's whole tree),
 * that the write that finally lands actually tombstones those rows in the real SQLDelight store,
 * and that the reminders it cancels are the real scheduler's. Time is still virtual —
 * `StandardTestDispatcher` sharing `runTest`'s scheduler — so the five-second window costs nothing
 * and "before" and "after" are exact rather than racy.
 *
 * The ViewModel runs on `runTest`'s `backgroundScope` rather than on the test coroutine itself:
 * its `init` starts collectors that never finish, and `runTest` waits for its own children
 * before it returns. `backgroundScope` is the scheduler-sharing scope that is exempt from that
 * wait and is cancelled for us — which also stands in for the `close()` the shell would call.
 *
 * **`jvmTest`, not `jvmSharedTest`**, unlike the two state tests beside it. The ViewModel takes a
 * concrete `CadenceSyncEngine`, and constructing one builds a real Ktor HTTP client — harmless
 * here, since signed out it makes no request at all. The rules under test are the ViewModel's own
 * and are identical on both targets, so running them on the desktop compilation is the whole
 * value.
 */
class CadenceViewModelUndoTest {

    private val stores = TestStores()
    /** The real store transaction behind a gate one test needs — see [GatedStoreTransaction]. */
    private val storeTransaction = GatedStoreTransaction(stores.storeTransaction)
    private val repository = stores.repository(storeTransaction = storeTransaction)
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

    /** What the app can see: every read the store offers filters tombstones, so this is what a
     *  committed delete takes away. A set — the question is which rows survive, never in what order. */
    private suspend fun liveTaskIds() = stores.taskStore.getAll().map { it.id }.toSet()

    // ── Deleting a task ──────────────────────────────────────────────────────────────

    @Test
    fun `a deleted task disappears at once but is not written until the window elapses`() = runTest {
        stores.seedTasks(task("parent"), task("step", parentId = "parent"), task("other"))
        val viewModel = viewModel()

        viewModel.deleteTask(task("parent"))
        runCurrent()

        // Hidden immediately — the row and its steps — while the database still holds them.
        assertEquals(setOf("parent", "step"), viewModel.state.value.pendingDeleteIds)
        assertEquals(listOf("other"), viewModel.state.value.tasks.map { it.id })
        assertEquals(setOf("parent", "step", "other"), liveTaskIds())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("other"), liveTaskIds())
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    @Test
    fun `committing a task delete cancels the alarms of the task and every step under it`() = runTest {
        stores.seedTasks(
            task("parent", due = LocalDate.of(2026, 8, 17)),
            task("step", parentId = "parent", due = LocalDate.of(2026, 8, 18)),
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
    fun `undo leaves the settled delete hidden while its write is still in flight`() = runTest {
        // #114, the wiring half: `UndoSlotTest` pins the same rule against a recording `commit`
        // lambda with no store at all; this is what proves the real gate — the tombstone write
        // itself — sits in the same place. `undo()` used to clear the *whole* `pendingDeleteIds`
        // flow, so the first delete's rows came back on screen and then vanished again a moment
        // later, once the write it never rescued landed. The gate below is what makes that moment
        // reachable — the only place in these tests that stands between the repository and SQLite.
        stores.seedTasks(task("first"), task("second"))
        val viewModel = viewModel()

        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storeTransaction.beforeRun = {
            // Only the settled delete waits; the rest of the test runs at full speed.
            storeTransaction.beforeRun = null
            reached.complete(Unit)
            release.await()
        }

        viewModel.deleteTask(task("first"))
        runCurrent()
        viewModel.deleteTask(task("second"))
        runCurrent()
        assertTrue(reached.isCompleted)

        viewModel.undo()
        runCurrent()

        // Only the second delete is taken back. The first is on its way to the database and
        // nothing is rescuing it, so its row stays hidden rather than flashing back into
        // every list.
        assertEquals(setOf("first"), viewModel.state.value.pendingDeleteIds)
        assertEquals(setOf("first", "second"), liveTaskIds())

        release.complete(Unit)
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() * 2)
        runCurrent()

        assertEquals(setOf("second"), liveTaskIds())
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    @Test
    fun `Inbox cleanup includes hidden history and checklists and commits as one undo action`() = runTest {
        val doneAt = Instant.parse("2026-09-01T12:00:00Z")
        stores.seedTasks(
            task("done").copy(completedAt = doneAt),
            task("step", parentId = "done").copy(completedAt = doneAt),
            task("history").copy(completedAt = doneAt),
            task("next").copy(spawnedFromId = "history"),
            task("open"),
            task("open-step", parentId = "open").copy(completedAt = doneAt),
            task("project-done", projectId = "work").copy(completedAt = doneAt),
        )
        settings.setShowCompleted(false)
        val viewModel = viewModel()
        assertEquals(setOf("done", "history"), viewModel.state.value.completedInboxTasks().map { it.id }.toSet())

        viewModel.deleteCompletedInboxTasks()
        runCurrent()
        assertEquals(setOf("done", "step", "history"), viewModel.state.value.pendingDeleteIds)
        assertTrue(viewModel.state.value.completedInboxTasks().isEmpty())
        assertEquals(7, liveTaskIds().size)

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()
        assertEquals(setOf("next", "open", "open-step", "project-done"), liveTaskIds())
        assertEquals(setOf("done", "step", "history"), reminders.cancelled.toSet())
    }

    @Test
    fun `undo restores all completed Inbox tasks without deleting anything`() = runTest {
        val doneAt = Instant.parse("2026-09-01T12:00:00Z")
        stores.seedTasks(task("first").copy(completedAt = doneAt), task("second").copy(completedAt = doneAt))
        val viewModel = viewModel()
        viewModel.deleteCompletedInboxTasks()
        runCurrent()
        // A repeated click while the state flow catches up must not settle the pending batch.
        viewModel.deleteCompletedInboxTasks()
        runCurrent()
        viewModel.undo()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("first", "second"), liveTaskIds())
        assertEquals(2, viewModel.state.value.completedInboxTasks().size)
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
        assertTrue(reminders.cancelled.isEmpty())
    }

    @Test
    fun `Inbox cleanup preserves tasks moved or reopened during the undo window`() = runTest {
        val doneAt = Instant.parse("2026-09-01T12:00:00Z")
        val reopened = task("reopened").copy(completedAt = doneAt)
        val moved = task("moved").copy(completedAt = doneAt)
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(reopened, moved, task("done").copy(completedAt = doneAt))
        val viewModel = viewModel()
        viewModel.deleteCompletedInboxTasks()
        runCurrent()
        repository.setCompleted(reopened, false)
        repository.moveToProject(moved, "work")
        stores.seedTasks(task("later").copy(completedAt = doneAt))
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("reopened", "moved", "later"), liveTaskIds())
        assertEquals(listOf("done"), reminders.cancelled)
        assertTrue(viewModel.state.value.pendingDeleteIds.isEmpty())
    }

    // ── Deleting a project ───────────────────────────────────────────────────────────

    @Test
    fun `deleting a project cancels the reminder of a subtask filed under it`() = runTest {
        // The regression this test exists for (#101): `tasksIn` goes through `rootTasks()`, so a
        // *subtask* of a task in the project is not in the list the UI can see — and an alarm
        // outlives the row unless something cancels it. The repository is the one that knows the
        // whole set, which is exactly why `deleteProject` returns it.
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(
            task("parent", projectId = "work", due = LocalDate.of(2026, 8, 17)),
            task("step", projectId = "work", parentId = "parent", due = LocalDate.of(2026, 8, 18)),
        )
        val viewModel = viewModel()

        viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = true)
        runCurrent()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(setOf("parent", "step"), reminders.cancelled.toSet())
        assertEquals(emptySet<String>(), liveTaskIds())
    }

    @Test
    fun `a deleted project hides its subtasks for the undo window too`() = runTest {
        // Same cause as above: the ids that hide rows and the ids that cancel alarms want the
        // same source. Today and Upcoming do not filter subtasks out, so a dated step whose
        // project is being deleted would otherwise stay on screen and then blink away.
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(
            task("parent", projectId = "work"),
            task("step", projectId = "work", parentId = "parent"),
            task("elsewhere"),
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
            stores.seedProjects(Project(id = "work", name = "Work"))
            stores.seedTasks(task("kept", projectId = "work"))
            val viewModel = viewModel()

            viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = false)
            runCurrent()

            assertEquals(setOf("work"), viewModel.state.value.pendingDeleteIds)
            assertEquals(listOf("kept"), viewModel.state.value.tasks.map { it.id })

            advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
            runCurrent()

            assertEquals(setOf("kept"), liveTaskIds())
            assertNull(stores.taskStore.byId("kept")!!.projectId)
            assertEquals(emptyList<String>(), reminders.cancelled)
        }

    @Test
    fun `undoing a project delete leaves the project and its tasks exactly where they were`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(task("kept", projectId = "work"))
        val viewModel = viewModel()

        viewModel.deleteProject(Project(id = "work", name = "Work"), deleteTasks = true)
        runCurrent()
        viewModel.undo()
        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() * 2)
        runCurrent()

        assertEquals(setOf("kept"), liveTaskIds())
        assertEquals(listOf("work"), viewModel.state.value.projects.map { it.id })
        assertEquals("work", stores.taskStore.byId("kept")!!.projectId)
    }

    // ── The danger zone ──────────────────────────────────────────────────────────────

    @Test
    fun `wiping everything empties the lists at once and tombstones after the window`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(task("a", projectId = "work"), task("b"))
        val viewModel = viewModel()

        viewModel.wipeEverything()
        runCurrent()

        assertEquals(emptyList<String>(), viewModel.state.value.tasks.map { it.id })
        assertEquals(emptyList<String>(), viewModel.state.value.projects.map { it.id })
        assertEquals(setOf("a", "b"), liveTaskIds())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(emptySet<String>(), liveTaskIds())
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
    fun `tasks, lead minutes and whether reminders are on reach the scheduler as they are`() = runTest {
        // What "off" means — an empty plan that cancels everything armed — is the reconciler's
        // rule and is pinned in `:core`. All the ViewModel owes the port is to forward the three
        // arguments untouched and to follow Settings; it used to strip the tasks' times instead,
        // encoding "off" as something the port could not tell from "no reminder set".
        stores.seedTasks(
            Task(
                id = "a",
                title = "a",
                dueDate = LocalDate.of(2026, 8, 17),
                dueTime = LocalTime.of(18, 0),
                reminderTime = LocalTime.of(9, 0),
            ),
        )
        val viewModel = viewModel()

        assertEquals(listOf(LocalTime.of(9, 0)), reminders.lastSynced.map { it.reminderTime })
        assertEquals(emptyList<Int>(), reminders.lastLeadMinutes)
        assertEquals(true, reminders.lastEnabled)

        viewModel.setRemindersEnabled(false)
        runCurrent()

        assertEquals(false, reminders.lastEnabled)
        assertEquals(listOf(LocalTime.of(9, 0)), reminders.lastSynced.map { it.reminderTime })
        assertEquals(listOf(LocalTime.of(18, 0)), reminders.lastSynced.map { it.dueTime })

        viewModel.setReminderLeadMinutes(listOf(20, 10, 5))
        runCurrent()

        assertEquals(listOf(20, 10, 5), reminders.lastLeadMinutes)
    }

    @Test
    fun `dismissing the snackbar does not rush the write`() = runTest {
        stores.seedTasks(task("doomed"))
        val viewModel = viewModel()

        viewModel.deleteTask(task("doomed"))
        runCurrent()
        viewModel.dismissSnackbar()
        runCurrent()

        assertNull(viewModel.state.value.snackbarMessage)
        assertEquals(setOf("doomed"), liveTaskIds())
        assertFalse(viewModel.state.value.pendingDeleteIds.isEmpty())

        advanceTimeBy(CadenceViewModel.UNDO_WINDOW.toMillis() + 1)
        runCurrent()

        assertEquals(emptySet<String>(), liveTaskIds())
    }
}

/**
 * The real [StoreTransaction] with one hook in front of [run], and nothing else of its own.
 *
 * `CadenceViewModel`'s undo window has a state — *this* delete is being written while *that* one
 * can still be taken back — that a test cannot otherwise stand inside: the store answers without
 * ever yielding, so `runCurrent()` drains a settled delete to completion in the same breath as the
 * one that settled it. `deleteTask`'s whole write now lands in one `storeTransaction.run { … }`
 * call, so gating in front of it reaches the same in-flight state gating the old tombstone call
 * alone used to — one hook, still the only place in these tests that stands between the
 * repository and SQLite. Null by default, so every other call, and every other test, goes
 * straight through to the database.
 */
private class GatedStoreTransaction(private val delegate: StoreTransaction) : StoreTransaction {

    var beforeRun: (suspend () -> Unit)? = null

    override suspend fun <T> run(block: TransactionScope.() -> T): T {
        beforeRun?.invoke()
        return delegate.run(block)
    }
}
