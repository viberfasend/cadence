package de.andi1984.cadence

import de.andi1984.cadence.domain.backup.BackupCodec
import de.andi1984.cadence.domain.backup.BackupError
import de.andi1984.cadence.domain.backup.BackupReadResult
import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class BackupCodecTest {

    private val exportedAt = Instant.parse("2026-08-05T09:00:00Z")

    private val project = Project(id = 7L, name = "Home", colorHex = "#006A60", sortOrder = 2)

    private val task = Task(
        id = 3L,
        title = "Water the plants",
        notes = "Only the balcony ones",
        priority = Priority.P2,
        projectId = 7L,
        dueDate = LocalDate.of(2026, 8, 6),
        dueTime = LocalTime.of(9, 30),
        reminderTime = LocalTime.of(8, 0),
        completedAt = Instant.parse("2026-08-04T07:12:00Z"),
        createdAt = Instant.parse("2026-08-01T05:00:00Z"),
        sortOrder = 1,
        recurrence = RecurrenceRule(
            mode = RecurrenceMode.SCHEDULE,
            interval = 2,
            unit = RecurrenceUnit.WEEK,
            daysOfWeek = setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY),
            monthlyMode = MonthlyMode.NTH_WEEKDAY,
            nthWeek = 2,
            nthDayOfWeek = DayOfWeek.MONDAY,
            keepMissed = false,
        ),
    )

    private fun roundTrip(snapshot: BackupSnapshot): BackupSnapshot {
        val json = BackupCodec.encode(snapshot, exportedAt)
        val result = BackupCodec.decode(json)
        assertTrue("decode failed: $result", result is BackupReadResult.Ok)
        return (result as BackupReadResult.Ok).snapshot
    }

    @Test
    fun `a full snapshot survives a round trip`() {
        val restored = roundTrip(BackupSnapshot(projects = listOf(project), tasks = listOf(task)))

        assertEquals(listOf(project), restored.projects)
        assertEquals(listOf(task), restored.tasks)
    }

    @Test
    fun `a task without dates or recurrence survives a round trip`() {
        val bare = Task(id = 11L, title = "Someday", createdAt = Instant.EPOCH)

        val restored = roundTrip(BackupSnapshot(tasks = listOf(bare)))

        assertEquals(listOf(bare), restored.tasks)
    }

    @Test
    fun `completion-anchored recurrence survives a round trip`() {
        val chore = task.copy(
            recurrence = RecurrenceRule(
                mode = RecurrenceMode.AFTER_COMPLETION,
                interval = 3,
                unit = RecurrenceUnit.DAY,
            ),
        )

        val restored = roundTrip(BackupSnapshot(projects = listOf(project), tasks = listOf(chore)))

        assertEquals(chore.recurrence, restored.tasks.single().recurrence)
    }

    @Test
    fun `dates are written as ISO strings a web app can read`() {
        val json = BackupCodec.encode(BackupSnapshot(tasks = listOf(task)), exportedAt)

        assertTrue(json, json.contains("\"dueDate\": \"2026-08-06\""))
        assertTrue(json, json.contains("\"dueTime\": \"09:30\""))
        assertTrue(json, json.contains("\"exportedAt\": \"2026-08-05T09:00:00Z\""))
        assertTrue(json, json.contains("\"format\": \"cadence.backup\""))
    }

    @Test
    fun `a task pointing at a missing project lands in the inbox`() {
        val orphan = task.copy(projectId = 99L)

        val restored = roundTrip(BackupSnapshot(projects = listOf(project), tasks = listOf(orphan)))

        assertNull(restored.tasks.single().projectId)
    }

    @Test
    fun `projects without a usable id and tasks without a title are dropped`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "projects": [{"id": 0, "name": "Nameless"}],
              "tasks": [{"id": 1, "title": ""}, {"id": 2, "title": "Keep me"}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertTrue(restored.projects.isEmpty())
        assertEquals(listOf("Keep me"), restored.tasks.map { it.title })
    }

    @Test
    fun `an unreadable date empties that field instead of failing the import`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [{"id": 1, "title": "Broken date", "dueDate": "the 6th"}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertNull(restored.tasks.single().dueDate)
    }

    @Test
    fun `subtasks keep their parent through a round trip`() {
        val step = Task(id = 4L, title = "Compare flights", parentId = 3L, createdAt = Instant.EPOCH)

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(project), tasks = listOf(task, step)),
        )

        assertEquals(listOf(null, 3L), restored.tasks.map { it.parentId })
    }

    @Test
    fun `a subtask whose parent is missing becomes a task of its own`() {
        val orphan = Task(id = 4L, title = "Compare flights", parentId = 99L)

        val restored = roundTrip(BackupSnapshot(tasks = listOf(orphan)))

        assertNull(restored.tasks.single().parentId)
    }

    @Test
    fun `a chain deeper than one level is flattened onto its root`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [
                {"id": 1, "title": "Move flat"},
                {"id": 2, "title": "Pack", "parentId": 1},
                {"id": 3, "title": "Pack the kitchen", "parentId": 2}
              ]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertEquals(listOf(null, 1L, 1L), restored.tasks.map { it.parentId })
    }

    @Test
    fun `parents that point at each other are set free rather than imported as a cycle`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [
                {"id": 1, "title": "A", "parentId": 2},
                {"id": 2, "title": "B", "parentId": 1},
                {"id": 3, "title": "C", "parentId": 3}
              ]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertEquals(listOf(null, null, null), restored.tasks.map { it.parentId })
    }

    @Test
    fun `unknown keys are ignored so newer files still restore`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tags": ["work"],
              "tasks": [{"id": 1, "title": "Tagged", "tagIds": [4]}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertEquals(listOf("Tagged"), restored.tasks.map { it.title })
    }

    @Test
    fun `text that is not JSON is refused`() {
        val result = BackupCodec.decode("not a backup at all")

        assertEquals(BackupError.NOT_JSON, (result as BackupReadResult.Failed).reason)
    }

    @Test
    fun `JSON from another app is refused`() {
        val result = BackupCodec.decode("""{"format": "todoist.export", "version": 1}""")

        assertEquals(BackupError.NOT_A_BACKUP, (result as BackupReadResult.Failed).reason)
    }

    @Test
    fun `a backup from a newer version is refused rather than guessed at`() {
        val result = BackupCodec.decode("""{"format": "cadence.backup", "version": 2}""")

        assertEquals(BackupError.NEWER_VERSION, (result as BackupReadResult.Failed).reason)
    }
}
