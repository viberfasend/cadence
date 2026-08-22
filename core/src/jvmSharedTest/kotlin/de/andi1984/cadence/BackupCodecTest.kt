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
import de.andi1984.cadence.domain.model.Section
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

    private val project = Project(id = "7", name = "Home", colorHex = "#006A60", sortOrder = 2)

    private val task = Task(
        id = "3",
        title = "Water the plants",
        notes = "Only the balcony ones",
        priority = Priority.P2,
        projectId = "7",
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

    /** The regression: `decode` used to filter out every tombstoned row, so a file carrying a
     *  delete could never make that delete compete on `updatedAt` in `BackupStore.mergeAll` — the
     *  deleted record came back to life on import instead. */
    @Test
    fun `a tombstoned task and a tombstoned project survive a round trip`() {
        val tombstonedProject = project.copy(deletedAt = Instant.parse("2026-08-05T08:00:00Z"))
        val tombstonedTask = task.copy(deletedAt = Instant.parse("2026-08-05T08:00:00Z"))

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(tombstonedProject), tasks = listOf(tombstonedTask)),
        )

        assertEquals(tombstonedProject, restored.projects.single())
        assertEquals(tombstonedTask, restored.tasks.single())
    }

    @Test
    fun `a tombstoned section survives a round trip`() {
        val section = Section(
            id = "s1",
            projectId = "7",
            name = "Gone now",
            deletedAt = Instant.parse("2026-08-05T08:00:00Z"),
        )

        val restored = roundTrip(BackupSnapshot(projects = listOf(project), sections = listOf(section)))

        assertEquals(listOf(section), restored.sections)
    }

    @Test
    fun `a task without dates or recurrence survives a round trip`() {
        val bare = Task(id = "11", title = "Someday", createdAt = Instant.EPOCH)

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
    fun `sections and the tasks grouped under them survive a round trip`() {
        val section = Section(id = "s1", projectId = "7", name = "This week", sortOrder = 3)
        val grouped = task.copy(sectionId = "s1")

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(project), sections = listOf(section), tasks = listOf(grouped)),
        )

        assertEquals(listOf(section), restored.sections)
        assertEquals("s1", restored.tasks.single().sectionId)
    }

    /** The regression: `encode` once dropped the whole list while `decode` still read it, so an
     *  export silently lost every heading and every task's place in one. */
    @Test
    fun `the encoded file carries the sections`() {
        val section = Section(id = "s1", projectId = "7", name = "This week", sortOrder = 3)

        val json = BackupCodec.encode(
            BackupSnapshot(projects = listOf(project), sections = listOf(section), tasks = listOf(task.copy(sectionId = "s1"))),
            exportedAt,
        )

        assertTrue(json, json.contains("\"name\": \"This week\""))
        assertTrue(json, json.contains("\"sectionId\": \"s1\""))
    }

    @Test
    fun `a section naming a project the file lacks is dropped, and its tasks keep the project`() {
        val orphanSection = Section(id = "s1", projectId = "99", name = "Nowhere")
        val grouped = task.copy(sectionId = "s1")

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(project), sections = listOf(orphanSection), tasks = listOf(grouped)),
        )

        assertTrue(restored.sections.isEmpty())
        assertEquals("7", restored.tasks.single().projectId)
        assertNull(restored.tasks.single().sectionId)
    }

    @Test
    fun `a task grouped under a section of another project loses the heading`() {
        val other = Project(id = "8", name = "Work", colorHex = "#123456")
        val section = Section(id = "s1", projectId = "8", name = "Work week")
        val grouped = task.copy(sectionId = "s1")

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(project, other), sections = listOf(section), tasks = listOf(grouped)),
        )

        assertEquals(listOf("s1"), restored.sections.map { it.id })
        assertEquals("7", restored.tasks.single().projectId)
        assertNull(restored.tasks.single().sectionId)
    }

    @Test
    fun `a task pointing at a missing project lands in the inbox`() {
        val orphan = task.copy(projectId = "99")

        val restored = roundTrip(BackupSnapshot(projects = listOf(project), tasks = listOf(orphan)))

        assertNull(restored.tasks.single().projectId)
    }

    @Test
    fun `a recurrence chain keeps its links across a round trip`() {
        val finished = task.copy(id = "40", projectId = null)
        val next = task.copy(id = "41", projectId = null, completedAt = null, spawnedFromId = "40")

        val restored = roundTrip(BackupSnapshot(tasks = listOf(finished, next)))

        assertEquals(listOf(null, "40"), restored.tasks.map { it.spawnedFromId })
    }

    @Test
    fun `a recurrence link to an occurrence the file lacks is dropped`() {
        val orphan = task.copy(projectId = null, spawnedFromId = "404")

        val restored = roundTrip(BackupSnapshot(tasks = listOf(orphan)))

        assertNull(restored.tasks.single().spawnedFromId)
    }

    @Test
    fun `projects without a usable id and tasks without a title are dropped`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "projects": [{"id": "", "name": "Nameless"}],
              "tasks": [{"id": "1", "title": ""}, {"id": "2", "title": "Keep me"}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertTrue(restored.projects.isEmpty())
        assertEquals(listOf("Keep me"), restored.tasks.map { it.title })
    }

    @Test
    fun `a task without an id is given a fresh one rather than colliding with another`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [{"title": "First"}, {"title": "Second"}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        val ids = restored.tasks.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `an unreadable date empties that field instead of failing the import`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [{"id": "1", "title": "Broken date", "dueDate": "the 6th"}]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertNull(restored.tasks.single().dueDate)
    }

    @Test
    fun `subtasks keep their parent through a round trip`() {
        val step = Task(id = "4", title = "Compare flights", parentId = "3", createdAt = Instant.EPOCH)

        val restored = roundTrip(
            BackupSnapshot(projects = listOf(project), tasks = listOf(task, step)),
        )

        assertEquals(listOf(null, "3"), restored.tasks.map { it.parentId })
    }

    @Test
    fun `a subtask whose parent is missing becomes a task of its own`() {
        val orphan = Task(id = "4", title = "Compare flights", parentId = "99")

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
                {"id": "1", "title": "Move flat"},
                {"id": "2", "title": "Pack", "parentId": "1"},
                {"id": "3", "title": "Pack the kitchen", "parentId": "2"}
              ]
            }
        """.trimIndent()

        val restored = (BackupCodec.decode(json) as BackupReadResult.Ok).snapshot

        assertEquals(listOf(null, "1", "1"), restored.tasks.map { it.parentId })
    }

    @Test
    fun `parents that point at each other are set free rather than imported as a cycle`() {
        val json = """
            {
              "format": "cadence.backup",
              "version": 1,
              "tasks": [
                {"id": "1", "title": "A", "parentId": "2"},
                {"id": "2", "title": "B", "parentId": "1"},
                {"id": "3", "title": "C", "parentId": "3"}
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
              "tasks": [{"id": "1", "title": "Tagged", "tagIds": [4]}]
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
        val result = BackupCodec.decode("""{"format": "cadence.backup", "version": 3}""")

        assertEquals(BackupError.NEWER_VERSION, (result as BackupReadResult.Failed).reason)
    }
}
