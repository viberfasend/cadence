package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The wire shape: what leaves the device and what comes back has to be the same record. */
class RemoteRecordsTest {

    private val now = Instant.parse("2026-08-11T09:15:30.123Z")

    @Test
    fun `a task survives the round trip through the wire shape`() {
        val task = Task(
            id = "0198f2a0-0000-7000-8000-000000000001",
            title = "Water the plants",
            notes = "The big one by the window",
            priority = Priority.fromLevel(2),
            projectId = "0198f2a0-0000-7000-8000-000000000002",
            parentId = null,
            spawnedFromId = "0198f2a0-0000-7000-8000-000000000003",
            dueDate = LocalDate.of(2026, 8, 20),
            dueTime = LocalTime.of(9, 30),
            reminderTime = LocalTime.of(9, 0),
            completedAt = now,
            createdAt = now,
            sortOrder = 3,
            recurrence = RecurrenceRule(
                mode = RecurrenceMode.AFTER_COMPLETION,
                interval = 3,
                unit = RecurrenceUnit.MONTH,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                monthlyMode = MonthlyMode.NTH_WEEKDAY,
                dayOfMonth = null,
                nthWeek = 2,
                nthDayOfWeek = DayOfWeek.FRIDAY,
                keepMissed = false,
            ),
            updatedAt = now,
            deletedAt = null,
        )

        assertEquals(task, task.toRemote().toDomain())
    }

    @Test
    fun `a project survives the round trip`() {
        val project = Project(
            id = "0198f2a0-0000-7000-8000-000000000002",
            name = "Home",
            colorHex = "#3F51B5",
            parentId = null,
            sortOrder = 1,
            updatedAt = now,
            deletedAt = null,
        )

        assertEquals(project, project.toRemote().toDomain())
    }

    @Test
    fun `a tombstone travels as a version of the record, not as an absence`() {
        val task = Task(id = "t1", title = "Gone", createdAt = now, updatedAt = now, deletedAt = now)

        val wire = task.toRemote()

        assertEquals(now.toString(), wire.deletedAt)
        assertEquals(now, wire.toDomain().deletedAt)
    }

    /**
     * The ping-pong ADR 0002 decision 8 exists to prevent: Postgres keeps microseconds, this
     * database keeps milliseconds, and a row that came back strictly newer than the local copy
     * would be merged over it, restamped, pushed again, forever.
     */
    @Test
    fun `microseconds from the server are truncated to milliseconds on the way in`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            createdAt = "2026-08-11T09:15:30.123456+00:00",
            updatedAt = "2026-08-11T09:15:30.123456+00:00",
        )

        assertEquals(Instant.parse("2026-08-11T09:15:30.123Z"), wire.toDomain().updatedAt)
        assertEquals(Instant.parse("2026-08-11T09:15:30.123Z"), wire.toDomain().createdAt)
    }

    /** PostgREST renders a `timestamptz` with an offset, which `Instant.parse` alone refuses. */
    @Test
    fun `an offset timestamp is read as the same instant as its Z spelling`() {
        val withOffset = RemoteTask(
            id = "t1",
            title = "Water the plants",
            createdAt = "2026-08-11T11:15:30+02:00",
            updatedAt = "2026-08-11T11:15:30+02:00",
        )

        assertEquals(Instant.parse("2026-08-11T09:15:30Z"), withOffset.toDomain().updatedAt)
    }

    /** One unreadable field must not cost the whole task — the rule the backup codec follows. */
    @Test
    fun `an unreadable date leaves that field empty rather than dropping the task`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            dueDate = "not a date",
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

        val task = wire.toDomain()

        assertNull(task.dueDate)
        assertEquals("Water the plants", task.title)
        assertEquals(now, task.updatedAt)
    }
}
