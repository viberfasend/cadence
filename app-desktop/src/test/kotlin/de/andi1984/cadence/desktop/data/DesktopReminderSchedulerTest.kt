package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The desktop's stand-in for AlarmManager: a coroutine that polls the current task list and
 * fires a tray balloon for whatever just came due.
 *
 * Two things are worth pinning down. A reminder must fire **once** — the poll runs every thirty
 * seconds and a task stays due forever once it is — and the reconcile must actually forget the
 * tasks it is no longer given, or a deleted row keeps a reminder that fires for something that
 * does not exist.
 *
 * The poll interval is virtual (`StandardTestDispatcher` on `runTest`'s scheduler), but "due" is
 * decided against the wall clock inside the scheduler, so the fixtures are a day either side of
 * today rather than a few milliseconds.
 *
 * `advanceTimeBy` rather than `advanceUntilIdle`: the poll loop is a `while (true)`, so there is
 * always another task scheduled and "until idle" never arrives.
 */
class DesktopReminderSchedulerTest {

    private val fired = mutableListOf<String>()
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
    }

    private fun TestScope.scheduler(): DesktopReminderScheduler {
        val pollScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        scope = pollScope
        return DesktopReminderScheduler(
            scope = pollScope,
            trayIcon = null,
            notify = { title -> fired += title },
        )
    }

    /** One poll, without letting the loop's `delay` run. */
    private fun TestScope.poll() = runCurrent()

    /** The next poll, thirty seconds of virtual time later. */
    private fun TestScope.nextPoll() = advanceTimeBy(POLL_INTERVAL_MILLIS + 1)

    private fun task(
        id: String,
        title: String = id,
        due: LocalDate?,
        reminder: LocalTime? = LocalTime.NOON,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        dueDate = due,
        reminderTime = reminder,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun yesterday() = LocalDate.now().minusDays(1)

    private fun tomorrow() = LocalDate.now().plusDays(1)

    @Test
    fun `a task whose reminder has passed fires on the next poll`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Water the plants", due = yesterday())))
        poll()

        assertEquals(listOf("Water the plants"), fired)
    }

    @Test
    fun `it fires once and only once, however many polls follow`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday())))
        poll()
        nextPoll()
        nextPoll()
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `a reconcile that hands the same task back does not fire it again`() = runTest {
        val scheduler = scheduler()
        val due = task("a", due = yesterday())

        scheduler.sync(listOf(due))
        poll()
        // Every task emission calls sync, so this is the ordinary case, not a corner one.
        scheduler.sync(listOf(due))
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `a reminder still in the future stays quiet`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())))
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task with no reminder time never fires, due or not`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), reminder = null)))
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a finished task never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), done = true)))
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task the reconcile no longer lists is forgotten and never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())))
        poll()
        // The row was deleted, or its reminder was cleared: sync is handed the list without it,
        // and that is what has to drop it from every map the poll reads.
        scheduler.sync(emptyList())
        scheduler.sync(listOf(task("a", due = yesterday())))
        nextPoll()

        // Re-listed with a reminder now in the past, so it does fire — once.
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `cancel drops a task that is already due before the poll reaches it`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday()), task("b", due = yesterday())))
        scheduler.cancel("a")
        poll()
        nextPoll()

        assertEquals(listOf("b"), fired)
    }

    @Test
    fun `a reminder moved into the future can fire again once it comes due`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday())))
        poll()
        assertEquals(listOf("a"), fired)

        // Snoozed: the same task, now due tomorrow. The `fired` mark has to come off, or the
        // reminder is suppressed forever by a decision made about a time that no longer applies.
        scheduler.sync(listOf(task("a", due = tomorrow())))
        nextPoll()
        assertEquals(listOf("a"), fired)

        // …and then the day arrives.
        scheduler.sync(listOf(task("a", due = yesterday())))
        nextPoll()
        assertEquals(listOf("a", "a"), fired)
    }

    @Test
    fun `several tasks due at once each fire once`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(
                task("a", due = yesterday()),
                task("b", due = yesterday()),
                task("c", due = tomorrow()),
            ),
        )
        poll()
        nextPoll()

        assertEquals(listOf("a", "b"), fired.sorted())
    }

    @Test
    fun `the title that fires is the one from the latest reconcile`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Old name", due = tomorrow())))
        poll()
        scheduler.sync(listOf(task("a", title = "New name", due = yesterday())))
        nextPoll()

        assertEquals(listOf("New name"), fired)
    }

    private companion object {
        /** Mirrors the scheduler's own interval; it is private there and this is a test that has
         *  to step past exactly one of them. */
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}
