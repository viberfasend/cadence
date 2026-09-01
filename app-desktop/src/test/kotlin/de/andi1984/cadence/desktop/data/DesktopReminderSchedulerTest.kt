package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 *
 * The scheduler runs on **`runTest`'s `backgroundScope`**, and that is load-bearing rather than
 * tidy. `runTest` drains the shared `TestScheduler` once the test body returns, and a `while
 * (true) { poll(); delay(30s) }` loop on a scope of our own always has one more `delay` queued —
 * so the drain never finishes and the test process spins at 100% CPU forever. It was the whole
 * of `:app-desktop:test`, and on a runner it burned the job's time limit rather than failing.
 * `backgroundScope` is cancelled *before* that drain, which is exactly what it exists for.
 */
class DesktopReminderSchedulerTest {

    private val fired = mutableListOf<String>()

    private fun TestScope.scheduler(): DesktopReminderScheduler =
        DesktopReminderScheduler(
            scope = backgroundScope,
            trayIcon = null,
            notify = { title -> fired += title },
        )

    /** One poll, without letting the loop's `delay` run. */
    private fun TestScope.poll() = runCurrent()

    /** The next poll, thirty seconds of virtual time later. */
    private fun TestScope.nextPoll() = advanceTimeBy(POLL_INTERVAL_MILLIS + 1)

    private fun task(
        id: String,
        title: String = id,
        due: LocalDate?,
        reminder: LocalTime? = LocalTime.NOON,
        dueTime: LocalTime? = null,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        dueDate = due,
        dueTime = dueTime,
        reminderTime = reminder,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun yesterday() = LocalDate.now().minusDays(1)

    private fun tomorrow() = LocalDate.now().plusDays(1)

    @Test
    fun `a task whose reminder has passed fires on the next poll`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Water the plants", due = yesterday())), emptyList())
        poll()

        assertEquals(listOf("Water the plants"), fired)
    }

    @Test
    fun `it fires once and only once, however many polls follow`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
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

        scheduler.sync(listOf(due), emptyList())
        poll()
        // Every task emission calls sync, so this is the ordinary case, not a corner one.
        scheduler.sync(listOf(due), emptyList())
        nextPoll()

        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `a reminder still in the future stays quiet`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task with no reminder time never fires, due or not`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), reminder = null)), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a finished task never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday(), done = true)), emptyList())
        poll()
        nextPoll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `a task the reconcile no longer lists is forgotten and never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = tomorrow())), emptyList())
        poll()
        // The row was deleted, or its reminder was cleared: sync is handed the list without it,
        // and that is what has to drop it from every map the poll reads.
        scheduler.sync(emptyList(), emptyList())
        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
        nextPoll()

        // Re-listed with a reminder now in the past, so it does fire — once.
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun `cancel drops a task that is already due before the poll reaches it`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday()), task("b", due = yesterday())), emptyList())
        scheduler.cancel("a")
        poll()
        nextPoll()

        assertEquals(listOf("b"), fired)
    }

    @Test
    fun `a reminder moved into the future can fire again once it comes due`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
        poll()
        assertEquals(listOf("a"), fired)

        // Snoozed: the same task, now due tomorrow. The `fired` mark has to come off, or the
        // reminder is suppressed forever by a decision made about a time that no longer applies.
        scheduler.sync(listOf(task("a", due = tomorrow())), emptyList())
        nextPoll()
        assertEquals(listOf("a"), fired)

        // …and then the day arrives.
        scheduler.sync(listOf(task("a", due = yesterday())), emptyList())
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
            emptyList(),
        )
        poll()
        nextPoll()

        assertEquals(listOf("a", "b"), fired.sorted())
    }

    @Test
    fun `the title that fires is the one from the latest reconcile`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(listOf(task("a", title = "Old name", due = tomorrow())), emptyList())
        poll()
        scheduler.sync(listOf(task("a", title = "New name", due = yesterday())), emptyList())
        nextPoll()

        assertEquals(listOf("New name"), fired)
    }

    // ── Lead-time notifications ─────────────────────────────────────────────────────

    @Test
    fun `a due time overdue by more than every configured lead fires once per lead`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminder = null, dueTime = LocalTime.NOON)),
            listOf(20, 10),
        )
        poll()

        assertEquals(listOf("a — 10m", "a — 20m"), fired.sorted())
    }

    @Test
    fun `reminderTime and a due-time lead both fire for the same task, independently`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminder = LocalTime.NOON, dueTime = LocalTime.of(13, 0))),
            listOf(10),
        )
        poll()

        assertEquals(listOf("a", "a — 10m"), fired.sorted())
    }

    @Test
    fun `a lead dropped from the settings list before the next poll never fires`() = runTest {
        val scheduler = scheduler()
        val due = task("a", due = yesterday(), reminder = null, dueTime = LocalTime.NOON)

        scheduler.sync(listOf(due), listOf(20, 10))
        // Settings changed before anything polled — the 20-minute lead must not survive the
        // reconcile just because it was armed a moment ago.
        scheduler.sync(listOf(due), listOf(10))
        poll()

        assertEquals(listOf("a — 10m"), fired)
    }

    @Test
    fun `cancel drops every lead an overdue task had armed`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminder = null, dueTime = LocalTime.NOON)),
            listOf(20, 10),
        )
        scheduler.cancel("a")
        poll()

        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `no lead minutes configured means a due time alone never fires`() = runTest {
        val scheduler = scheduler()

        scheduler.sync(
            listOf(task("a", due = yesterday(), reminder = null, dueTime = LocalTime.NOON)),
            emptyList(),
        )
        poll()

        assertEquals(emptyList<String>(), fired)
    }

    private companion object {
        /** Mirrors the scheduler's own interval; it is private there and this is a test that has
         *  to step past exactly one of them. */
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}
