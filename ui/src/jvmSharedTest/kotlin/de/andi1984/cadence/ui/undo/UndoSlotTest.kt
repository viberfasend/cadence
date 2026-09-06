package de.andi1984.cadence.ui.undo

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.SnackbarMessage
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.snackbar_project_name_empty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * `UndoSlot`'s state machine in isolation: an [UndoSlot.offer] → [UndoSlot.hiddenIds] →
 * deferred [UndoSlot.commit], with a recording lambda standing in for `CadenceViewModel`'s real
 * dispatch. No repository, no stores — `CadenceViewModelUndoTest` still covers the wiring that
 * actually deletes rows and cancels alarms; every test here is only about *when* an offer commits,
 * which is exactly the question `UndoSlot` exists to answer on its own.
 *
 * Time is virtual — `StandardTestDispatcher` sharing `runTest`'s scheduler — so the window costs
 * no wall clock and "before" and "after" are exact rather than racy. `UndoSlot` is handed
 * `backgroundScope` per the CLAUDE.md rule for a class given its own `CoroutineScope`: it is
 * cancelled *before* `runTest` drains the scheduler, so a deferred commit left pending at the end
 * of a test (most of them leave one) cannot hang the run the way a scope of our own would.
 */
class UndoSlotTest {

    private val window = Duration.ofSeconds(5)

    /** A minimal action carrying only what `offer`/`commit`/`hiddenIds` need — nothing is ever
     *  really tombstoned here, so its shape doesn't matter beyond the ids it names. */
    private fun action(vararg ids: String) = UndoAction.DeleteTask(
        task = Task(id = ids.first(), title = ids.first()),
        subtasks = ids.drop(1).map { Task(id = it, title = it) },
    )

    private fun TestScope.slot(commit: suspend (UndoAction) -> Unit = {}) =
        UndoSlot(scope = backgroundScope, window = window, commit = commit)

    @Test
    fun `an offer hides its ids at once and commits only once the window elapses`() = runTest {
        val committed = mutableListOf<UndoAction>()
        val slot = slot { committed += it }

        val a = action("x", "y")
        slot.offer(a, count = 2)
        runCurrent()

        assertEquals(setOf("x", "y"), slot.hiddenIds.value)
        assertTrue(committed.isEmpty())

        advanceTimeBy(window.toMillis() + 1)
        runCurrent()

        assertEquals(listOf(a), committed)
        assertTrue(slot.hiddenIds.value.isEmpty())
    }

    @Test
    fun `undo inside the window commits nothing and unhides the ids`() = runTest {
        val committed = mutableListOf<UndoAction>()
        val slot = slot { committed += it }

        slot.offer(action("x"), count = 1)
        runCurrent()
        slot.undo()
        runCurrent()

        // Past where the cancelled job would have fired, to prove it is really gone.
        advanceTimeBy(window.toMillis() * 2)
        runCurrent()

        assertTrue(committed.isEmpty())
        assertTrue(slot.hiddenIds.value.isEmpty())
        assertNull(slot.snackbar.value)
    }

    @Test
    fun `the snackbar carries the offered action and counts what it will take`() = runTest {
        val slot = slot()
        val a = action("x", "y", "z")

        slot.offer(a, count = 3)
        runCurrent()

        val message = slot.snackbar.value as SnackbarMessage.Counted
        assertEquals(3, message.count)
        assertEquals(a, message.undoAction)
    }

    @Test
    fun `a second offer commits the first out of band rather than racing it`() = runTest {
        val committed = mutableListOf<UndoAction>()
        val slot = slot { committed += it }
        val first = action("first")
        val second = action("second")

        slot.offer(first, count = 1)
        runCurrent()
        // Well inside the first window: nothing has committed yet.
        advanceTimeBy(window.toMillis() / 2)
        assertTrue(committed.isEmpty())

        slot.offer(second, count = 1)
        runCurrent()

        // The user moved on, so the first offer settles now, without waiting out its window.
        assertEquals(listOf(first), committed)
        assertEquals(setOf("second"), slot.hiddenIds.value)

        advanceTimeBy(window.toMillis() + 1)
        runCurrent()

        assertEquals(listOf(first, second), committed)
        assertTrue(slot.hiddenIds.value.isEmpty())
    }

    @Test
    fun `undo after a second offer only rescues the second — the first already committed`() = runTest {
        val committed = mutableListOf<UndoAction>()
        val slot = slot { committed += it }
        val first = action("first")
        val second = action("second")

        slot.offer(first, count = 1)
        runCurrent()
        slot.offer(second, count = 1)
        runCurrent()
        slot.undo()
        advanceTimeBy(window.toMillis() * 2)
        runCurrent()

        assertEquals(listOf(first), committed)
    }

    @Test
    fun `undo leaves the settled action hidden while its commit is still in flight`() = runTest {
        // #114. The test above drains the out-of-band commit before undoing, which is exactly
        // what hid this: `undo()` used to clear the *whole* hidden-ids set, so the first action's
        // ids came back on screen and then vanished again a moment later, once the commit it
        // never rescued landed. The gate below is what makes that moment reachable — `commit`
        // *is* the write here, so nothing extra (no `GatedTaskStore`-style wrapper) is needed to
        // stand between an offer and its commit the way one is in `CadenceViewModelUndoTest`.
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<UndoAction>()
        val first = action("first")
        val second = action("second")
        val slot = slot { action ->
            if (action === first) {
                reached.complete(Unit)
                release.await()
            }
            committed += action
        }

        slot.offer(first, count = 1)
        runCurrent()
        slot.offer(second, count = 1)
        runCurrent()
        assertTrue(reached.isCompleted)

        slot.undo()
        runCurrent()

        // Only the second offer is taken back. The first is on its way to committing and nothing
        // rescues it, so its id stays hidden rather than flashing back into every list.
        assertEquals(setOf("first"), slot.hiddenIds.value)

        release.complete(Unit)
        advanceTimeBy(window.toMillis() * 2)
        runCurrent()

        // Only the first ever commits — the second was undone before its own window elapsed, so
        // its deferred job never runs at all.
        assertEquals(listOf(first), committed)
        assertTrue(slot.hiddenIds.value.isEmpty())
    }

    @Test
    fun `an informational message waits for a live undo rather than replacing it`() = runTest {
        // #114. The two are not equals: a validation message is repeatable feedback about a form
        // still on screen, and the undo is a five-second, one-time chance to take an action back.
        val slot = slot()

        slot.offer(action("doomed"), count = 1)
        runCurrent()
        val undoMessage = slot.snackbar.value
        assertTrue(undoMessage is SnackbarMessage.Counted)

        slot.show(SnackbarMessage.Text(text = Res.string.snackbar_project_name_empty))
        runCurrent()

        assertEquals(undoMessage, slot.snackbar.value)

        slot.undo()
        runCurrent()

        // Not dropped either — it lands the moment the slot frees.
        assertTrue(slot.snackbar.value is SnackbarMessage.Text)
    }

    @Test
    fun `dismissing the snackbar does not rush the commit`() = runTest {
        val committed = mutableListOf<UndoAction>()
        val slot = slot { committed += it }
        val a = action("doomed")

        slot.offer(a, count = 1)
        runCurrent()
        slot.dismiss()
        runCurrent()

        assertNull(slot.snackbar.value)
        assertTrue(committed.isEmpty())
        assertTrue(slot.hiddenIds.value.isNotEmpty())

        advanceTimeBy(window.toMillis() + 1)
        runCurrent()

        assertEquals(listOf(a), committed)
    }
}
