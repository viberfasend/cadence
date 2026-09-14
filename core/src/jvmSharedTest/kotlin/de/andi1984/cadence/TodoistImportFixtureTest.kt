package de.andi1984.cadence

import de.andi1984.cadence.domain.backup.BackupCodec
import de.andi1984.cadence.domain.backup.BackupReadResult
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * `tools/todoist_import.py` writes this shape, and the app has to keep reading it.
 *
 * The fixture is a trimmed copy of that script's output — same keys, same date and instant
 * formats, same "everything hangs under one staging project", "a Todoist section is a section of
 * its project" and "INDENT 2 is a subtask" decisions. The `sections` array and the tasks'
 * `sectionId` are part of that shape now, and the version alongside them deliberately did not
 * move: both keys are additive, so an older install ignores them rather than refusing the file.
 * Nothing here exercises the script itself (it has its own tests, `--self-test`);
 * what it guards is the other half of the contract: that a change to [BackupCodec] cannot
 * quietly stop the converter's files from importing.
 */
class TodoistImportFixtureTest {

    private val fixture = """
        {
          "format": "cadence.backup",
          "version": 2,
          "exportedAt": "2026-08-13T20:05:11.382000Z",
          "projects": [
            {
              "id": "b1c6b2b4-2f27-5c0e-9f6a-1f0f6d3a7c02",
              "name": "Import 2026-08-13",
              "colorHex": "#006A60",
              "parentId": null,
              "sortOrder": 0,
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            },
            {
              "id": "cf0bf792-ffb2-5bc9-97d9-8b5ba5085ed3",
              "name": "wohnung",
              "colorHex": "#3E6373",
              "parentId": "b1c6b2b4-2f27-5c0e-9f6a-1f0f6d3a7c02",
              "sortOrder": 1,
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            }
          ],
          "sections": [
            {
              "id": "6f0f9bd0-2a1f-5a4f-9a6c-6b4f1f6d7a11",
              "projectId": "cf0bf792-ffb2-5bc9-97d9-8b5ba5085ed3",
              "name": "Ofen",
              "sortOrder": 1,
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            }
          ],
          "tasks": [
            {
              "id": "43ad82ed-339f-5fe2-9bee-53f9e58d6bb2",
              "title": "Bad putzen",
              "notes": "2018-06-08 · Ballistol benutzt\n\nDeadline: 2027-01-19",
              "priority": 2,
              "projectId": "cf0bf792-ffb2-5bc9-97d9-8b5ba5085ed3",
              "sectionId": null,
              "parentId": null,
              "spawnedFromId": null,
              "dueDate": "2026-08-13",
              "dueTime": "09:30",
              "reminderTime": null,
              "completedAt": null,
              "createdAt": "2026-08-13T20:05:11.382000Z",
              "sortOrder": 0,
              "recurrence": {
                "mode": "SCHEDULE",
                "interval": 1,
                "unit": "WEEK",
                "daysOfWeek": ["THURSDAY"],
                "monthlyMode": "DAY_OF_MONTH",
                "dayOfMonth": null,
                "nthWeek": null,
                "nthDayOfWeek": null,
                "keepMissed": true
              },
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            },
            {
              "id": "9be9fec3-25f7-5562-bb32-dc6eca7ff366",
              "title": "Fliesen",
              "notes": null,
              "priority": 4,
              "projectId": "cf0bf792-ffb2-5bc9-97d9-8b5ba5085ed3",
              "sectionId": null,
              "parentId": "43ad82ed-339f-5fe2-9bee-53f9e58d6bb2",
              "spawnedFromId": null,
              "dueDate": null,
              "dueTime": null,
              "reminderTime": null,
              "completedAt": null,
              "createdAt": "2026-08-13T20:05:11.382000Z",
              "sortOrder": 1,
              "recurrence": null,
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            },
            {
              "id": "047ee48f-9582-5a08-814a-138e349a9930",
              "title": "reinigen",
              "notes": null,
              "priority": 4,
              "projectId": "cf0bf792-ffb2-5bc9-97d9-8b5ba5085ed3",
              "sectionId": "6f0f9bd0-2a1f-5a4f-9a6c-6b4f1f6d7a11",
              "parentId": null,
              "spawnedFromId": null,
              "dueDate": "2026-08-13",
              "dueTime": null,
              "reminderTime": null,
              "completedAt": null,
              "createdAt": "2026-08-13T20:05:11.382000Z",
              "sortOrder": 2,
              "recurrence": {
                "mode": "AFTER_COMPLETION",
                "interval": 1,
                "unit": "YEAR",
                "daysOfWeek": [],
                "monthlyMode": "DAY_OF_MONTH",
                "dayOfMonth": null,
                "nthWeek": null,
                "nthDayOfWeek": null,
                "keepMissed": true
              },
              "updatedAt": "2026-08-13T20:05:11.382000Z",
              "deletedAt": null
            }
          ]
        }
    """.trimIndent()

    private val snapshot = (BackupCodec.decode(fixture) as BackupReadResult.Ok).snapshot

    @Test
    fun `a converted export is a readable backup`() {
        assertTrue(BackupCodec.decode(fixture) is BackupReadResult.Ok)
        assertEquals(2, snapshot.projects.size)
        assertEquals(1, snapshot.sections.size)
        assertEquals(3, snapshot.tasks.size)
    }

    @Test
    fun `an import lands in one staging project and nothing else`() {
        // The point of the staging project: an import is a pile to sort. Nothing may arrive
        // beside the projects already on the device, and nothing may land in the Inbox.
        val staging = snapshot.projects.single { it.parentId == null }
        assertEquals("Import 2026-08-13", staging.name)
        assertTrue(snapshot.projects.filter { it.id != staging.id }.all { it.parentId == staging.id })
        assertTrue(snapshot.tasks.all { it.projectId != null })
    }

    @Test
    fun `a Todoist section arrives as a section of the project it came from`() {
        // Todoist's sections are Primico's, one for one. The converter used to write each of
        // them as a sibling project named "wohnung · Ofen", because there were no sections and
        // the staging project had taken the one level of nesting projects allow.
        val wohnung = snapshot.projects.first { it.name == "wohnung" }
        val section = snapshot.sections.single()
        assertEquals("Ofen", section.name)
        assertEquals(wohnung.id, section.projectId)

        // The heading groups the row; it does not own it. A task whose projectId named the
        // section would be a task the project it was exported from no longer lists.
        val grouped = snapshot.tasks.first { it.title == "reinigen" }
        assertEquals(wohnung.id, grouped.projectId)
        assertEquals(section.id, grouped.sectionId)
        // A row exported above every section row is in the project's ungrouped band.
        assertNull(snapshot.tasks.first { it.title == "Bad putzen" }.sectionId)
    }

    @Test
    fun `an indented Todoist task arrives as a subtask of the row above it`() {
        val parent = snapshot.tasks.first { it.title == "Bad putzen" }
        val child = snapshot.tasks.first { it.title == "Fliesen" }
        assertEquals(parent.id, child.parentId)
        // A parent and its steps share a project — the converter files them together so the
        // repository's "moveToProject moves both" invariant starts out satisfied.
        assertEquals(parent.projectId, child.projectId)
    }

    @Test
    fun `a repeat phrase arrives as a recurrence rule with its next date`() {
        val weekly = snapshot.tasks.first { it.title == "Bad putzen" }.recurrence!!
        assertEquals(RecurrenceUnit.WEEK, weekly.unit)
        assertEquals(setOf(DayOfWeek.THURSDAY), weekly.daysOfWeek)
        assertEquals(LocalDate.of(2026, 8, 13), snapshot.tasks.first { it.title == "Bad putzen" }.dueDate)

        // Todoist's "every! 1 year" is the one phrase a calendar rule cannot express.
        val afterCompletion = snapshot.tasks.first { it.title == "reinigen" }.recurrence!!
        assertEquals(RecurrenceMode.AFTER_COMPLETION, afterCompletion.mode)
        assertEquals(RecurrenceUnit.YEAR, afterCompletion.unit)
    }

    @Test
    fun `priority, notes and the absent fields survive the trip`() {
        val task = snapshot.tasks.first { it.title == "Bad putzen" }
        assertEquals(Priority.P2, task.priority)
        val notes = task.notes!!
        assertTrue(notes.contains("Ballistol"))
        assertTrue(notes.contains("Deadline: 2027-01-19"))
        assertNull(task.completedAt)
        assertNull(task.deletedAt)
        assertNull(snapshot.tasks.first { it.title == "Fliesen" }.notes)
    }
}
