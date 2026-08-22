package de.andi1984.cadence

import de.andi1984.cadence.data.AttachmentStore
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.RepositoryResult
import de.andi1984.cadence.data.SectionStore
import de.andi1984.cadence.data.StoreResult
import de.andi1984.cadence.data.TagStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/**
 * Completion is where recurrence turns into rows, so this is where a duplicate can be created.
 * The rows the UI hands back are snapshots, and the same open snapshot arrives again on a second
 * tap — every test here works with a deliberately stale [Task].
 */
class CadenceRepositoryTest {

    private val fortnightly = RecurrenceRule(interval = 2, unit = RecurrenceUnit.WEEK)
    private val today = LocalDate.of(2026, 8, 7)

    private val taskStore = FakeTaskStore()
    private val attachmentStore = FakeAttachmentStore()
    private val blobStore = BlobStore(
        root = Files.createTempDirectory("cadence-blobs").toFile(),
        tmp = Files.createTempDirectory("cadence-blobs-tmp").toFile(),
    )
    private val projectStore = FakeProjectStore()
    private val sectionStore = FakeSectionStore(taskStore)
    private val tagStore = FakeTagStore()
    private val repository = CadenceRepository(
        taskStore,
        projectStore,
        sectionStore,
        tagStore,
        FakeBackupStore(),
        attachmentStore,
        blobStore,
    )

    private fun store(task: Task): Task = taskStore.row(taskStore.put(task))

    /** Stores [bytes] as a FILE attachment on [taskId] and returns the hash it landed at. */
    private fun attach(taskId: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): String {
        val result = blobStore.store(ByteArrayInputStream(bytes), maxBytes = 1024) as StoreResult.Ok
        attachmentStore.put(
            Attachment(
                taskId = taskId,
                kind = AttachmentKind.FILE,
                name = "file.bin",
                mimeType = "application/octet-stream",
                sha256 = result.sha256,
                sizeBytes = result.sizeBytes,
            ),
        )
        return result.sha256
    }

    private fun rowsTitled(title: String) = taskStore.rows().filter { it.title == title }

    /** The task from the bug report: fortnightly, in the Inbox, due today. */
    private fun recurring(
        due: LocalDate? = LocalDate.of(2026, 8, 7),
        priority: Priority = Priority.DEFAULT,
    ) = Task(title = "Rat poison", priority = priority, dueDate = due, recurrence = fortnightly)

    @Test
    fun `completing a recurring task leaves the finished row and one successor`() = runTest {
        val task = store(recurring())

        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Rat poison")
        assertEquals(2, rows.size)
        val done = rows.single { it.isDone }
        val next = rows.single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 7), done.dueDate)
        assertEquals(LocalDate.of(2026, 8, 21), next.dueDate)
        assertEquals(fortnightly, next.recurrence)
    }

    @Test
    fun `a second tap on the same open snapshot does not insert a second successor`() = runTest {
        val task = store(recurring())

        // Both calls carry the row as it was drawn — open — because the list has not
        // re-composed between the two taps.
        repository.setCompleted(task, true, today)
        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Rat poison")
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { !it.isDone })
    }

    @Test
    fun `completing a task with no recurrence twice only closes it`() = runTest {
        val task = store(Task(title = "Call the vet"))

        repository.setCompleted(task, true, today)
        repository.setCompleted(task, true, today)

        val rows = rowsTitled("Call the vet")
        assertEquals(1, rows.size)
        assertTrue(rows.single().isDone)
    }

    @Test
    fun `the completion time of a closed task is not overwritten by a later tap`() = runTest {
        val task = store(recurring(due = null))
        repository.setCompleted(task, true, today)
        val closedAt = rowsTitled("Rat poison").single { it.completedAt != null }.completedAt

        repository.setCompleted(task, true, today)

        val stillClosedAt = rowsTitled("Rat poison").single { it.completedAt != null }.completedAt
        assertEquals(closedAt, stillClosedAt)
    }

    @Test
    fun `the successor inherits edits made after the row was drawn`() = runTest {
        val drawn = store(recurring(due = LocalDate.of(2026, 8, 1), priority = Priority.P3))
        // The detail screen moved the task while the list still showed the old row.
        val edited = drawn.copy(priority = Priority.P1, dueDate = LocalDate.of(2026, 8, 5))
        taskStore.put(edited)

        repository.setCompleted(drawn, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 19), next.dueDate)
        assertEquals(Priority.P1, next.priority)
    }

    @Test
    fun `a checklist is handed over once, unticked and shifted`() = runTest {
        val parent = store(recurring())
        store(
            Task(
                title = "Check the traps",
                parentId = parent.id,
                dueDate = LocalDate.of(2026, 8, 6),
            ),
        )

        repository.setCompleted(parent, true, today)
        repository.setCompleted(parent, true, today)

        val steps = rowsTitled("Check the traps")
        assertEquals(2, steps.size)
        val handedOver = steps.single { !it.isDone }
        assertEquals(LocalDate.of(2026, 8, 20), handedOver.dueDate)
        // The step went with the new occurrence, not with the one that was just finished.
        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(next.id, handedOver.parentId)
        assertTrue(steps.single { it.isDone }.parentId == parent.id)
    }

    @Test
    fun `adding a subtask to an ordinary top-level task succeeds`() = runTest {
        val parent = store(Task(title = "Plan the trip"))

        val result = repository.addSubtask(parent, "Book flights")

        assertTrue(result is RepositoryResult.Success)
        val step = rowsTitled("Book flights").single()
        assertEquals(parent.id, step.parentId)
    }

    @Test
    fun `adding a subtask while viewing a subtask files it under the same parent`() = runTest {
        val parent = store(Task(title = "Plan the trip"))
        val firstStep = store(Task(title = "Book flights", parentId = parent.id))

        val result = repository.addSubtask(firstStep, "Book hotel")

        assertTrue(result is RepositoryResult.Success)
        val secondStep = rowsTitled("Book hotel").single()
        assertEquals(parent.id, secondStep.parentId)
    }

    @Test
    fun `the successor records which occurrence it replaces`() = runTest {
        val task = store(recurring())

        repository.setCompleted(task, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(task.id, next.spawnedFromId)
    }

    @Test
    fun `reopening a recurring task takes the occurrence it created with it`() = runTest {
        val task = store(recurring())
        repository.setCompleted(task, true, today)
        val next = rowsTitled("Rat poison").single { it.completedAt == null }

        val removed = repository.setCompleted(task, false, today)

        assertEquals(listOf(next.id), removed)
        val rows = rowsTitled("Rat poison")
        assertEquals(1, rows.size)
        assertEquals(task.id, rows.single().id)
        assertNull(rows.single().completedAt)
    }

    @Test
    fun `reopening leaves a successor that has itself been ticked off`() = runTest {
        val first = store(recurring())
        repository.setCompleted(first, true, today)
        val second = rowsTitled("Rat poison").single { it.completedAt == null }
        // The chain moved on: the second occurrence is done and has a successor of its own.
        repository.setCompleted(second, true, LocalDate.of(2026, 8, 21))

        val removed = repository.setCompleted(first, false, today)

        assertTrue(removed.isEmpty())
        assertEquals(3, rowsTitled("Rat poison").size)
    }

    @Test
    fun `reopening a recurring task also removes the successor's checklist`() = runTest {
        val parent = store(recurring())
        store(Task(title = "Check the traps", parentId = parent.id))
        repository.setCompleted(parent, true, today)

        repository.setCompleted(parent, false, today)

        // The handed-over copy is gone with its occurrence; the original step stays put.
        val steps = rowsTitled("Check the traps")
        assertEquals(1, steps.size)
        assertEquals(parent.id, steps.single().parentId)
    }

    @Test
    fun `reopening clears the completion and reopening again is a no-op`() = runTest {
        val task = store(Task(title = "Call the vet"))
        repository.setCompleted(task, true, today)
        val done = rowsTitled("Call the vet").single()

        repository.setCompleted(done, false, today)
        repository.setCompleted(done, false, today)

        val rows = rowsTitled("Call the vet")
        assertEquals(1, rows.size)
        assertNull(rows.single().completedAt)
        assertFalse(rows.single().isDone)
    }

    // ── Sections ───────────────────────────────────────────────────────────────────

    /** Adds a section to `p1` and returns the id the repository minted for it. */
    private suspend fun section(name: String = "This week", projectId: String = "p1"): String {
        val result = repository.upsertSection(Section(projectId = projectId, name = name))
        return (result as RepositoryResult.Success).data
    }

    @Test
    fun `deleting a section frees its tasks rather than taking them with it`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice", projectId = "p1", sectionId = sectionId))

        repository.deleteSection(sectionId)

        // The heading is gone and the work is not: it is still in the project, one band higher.
        assertNotNull(sectionStore.row(sectionId).deletedAt)
        assertTrue(sectionStore.rows().isEmpty())
        val freed = taskStore.row(task.id)
        assertNull(freed.sectionId)
        assertEquals("p1", freed.projectId)
        assertNull(freed.deletedAt)
    }

    @Test
    fun `deleting a section twice leaves the first tombstone's timestamp alone`() = runTest {
        val sectionId = section()

        repository.deleteSection(sectionId)
        val stamped = sectionStore.row(sectionId).deletedAt
        repository.deleteSection(sectionId)

        assertEquals(stamped, sectionStore.row(sectionId).deletedAt)
    }

    @Test
    fun `moving a task to another project drops the heading it had`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice", projectId = "p1", sectionId = sectionId))
        val step = store(Task(title = "Find the receipt", parentId = task.id, projectId = "p1", sectionId = sectionId))

        repository.moveToProject(taskStore.row(task.id), "p2")

        // A section belongs to one project, so the heading means nothing in the new one — and the
        // checklist follows its task rather than staying behind under a band it no longer shares.
        assertEquals("p2", taskStore.row(task.id).projectId)
        assertNull(taskStore.row(task.id).sectionId)
        assertEquals("p2", taskStore.row(step.id).projectId)
        assertNull(taskStore.row(step.id).sectionId)
    }

    @Test
    fun `filing a task under a section takes its checklist with it`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice", projectId = "p1"))
        val step = store(Task(title = "Find the receipt", parentId = task.id, projectId = "p1"))

        repository.moveToSection(taskStore.row(task.id), sectionId)

        assertEquals(sectionId, taskStore.row(task.id).sectionId)
        assertEquals(sectionId, taskStore.row(step.id).sectionId)
    }

    @Test
    fun `a task with no project cannot be filed under a section`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice"))

        repository.moveToSection(taskStore.row(task.id), sectionId)

        // There is nowhere for it to be grouped: the Inbox draws no headings.
        assertNull(taskStore.row(task.id).sectionId)
    }

    @Test
    fun `a section with a blank name is refused rather than stored`() = runTest {
        val result = repository.upsertSection(Section(projectId = "p1", name = "   "))

        assertTrue(result is RepositoryResult.ValidationError)
        assertTrue(sectionStore.rows().isEmpty())
    }

    @Test
    fun `the danger zone tombstones the sections too`() = runTest {
        val sectionId = section()

        repository.deleteEverything()

        assertNotNull(sectionStore.row(sectionId).deletedAt)
        assertTrue(sectionStore.rows().isEmpty())
    }

    // ── Attachments ────────────────────────────────────────────────────────────────

    @Test
    fun `the danger zone tombstones every task and project rather than removing rows`() = runTest {
        val first = store(Task(title = "Send the invoice"))
        val second = store(Task(title = "Book flights", parentId = first.id))

        val wiped = repository.deleteEverything()

        assertEquals(setOf(first.id, second.id), wiped.toSet())
        assertTrue(taskStore.rows().isEmpty())
        // A hard delete would look identical to the list above and lose the deletion on sync.
        assertNotNull(taskStore.row(first.id).deletedAt)
        assertNotNull(taskStore.row(second.id).deletedAt)
        assertNotNull(projectStore.wipedAt)
    }

    @Test
    fun `the danger zone reclaims the blobs the wiped tasks named`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val hash = attach(task.id)

        repository.deleteEverything()

        assertTrue(attachmentStore.forTask(task.id).isEmpty())
        assertNull(blobStore.file(hash))
    }

    @Test
    fun `wiping twice leaves the first tombstone's timestamp alone`() = runTest {
        val task = store(Task(title = "Send the invoice"))

        repository.deleteEverything()
        val stamped = taskStore.row(task.id).deletedAt
        repository.deleteEverything()

        assertEquals(stamped, taskStore.row(task.id).deletedAt)
    }

    @Test
    fun `deleting a task reclaims the blob its only attachment named`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val hash = attach(task.id)

        repository.deleteTask(task.id)

        assertTrue(attachmentStore.forTask(task.id).isEmpty())
        assertNull(blobStore.file(hash))
    }

    @Test
    fun `a blob shared by two tasks survives one of them being deleted`() = runTest {
        val first = store(Task(title = "Send the invoice"))
        val second = store(Task(title = "File the invoice"))
        val bytes = byteArrayOf(9, 9, 9)
        val hash = attach(first.id, bytes)
        attach(second.id, bytes)

        repository.deleteTask(first.id)

        assertNotNull(blobStore.file(hash))
    }

    @Test
    fun `deleting a task's subtasks reclaims their attachments too`() = runTest {
        val parent = store(Task(title = "Plan the trip"))
        val step = store(Task(title = "Book flights", parentId = parent.id))
        val hash = attach(step.id)

        repository.deleteTask(parent.id)

        assertNull(blobStore.file(hash))
    }

    @Test
    fun `deleting a project with its tasks reclaims their attachments`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val hash = attach(task.id)
        val projectStore = FakeProjectStore(tasksIn = listOf(task.id))
        val withProject = CadenceRepository(
            taskStore,
            projectStore,
            sectionStore,
            tagStore,
            FakeBackupStore(),
            attachmentStore,
            blobStore,
        )

        withProject.deleteProject(id = "p1", deleteTasks = true)

        assertNull(blobStore.file(hash))
    }

    @Test
    fun `moving a project's tasks to the Inbox keeps their attachments`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val hash = attach(task.id)
        val projectStore = FakeProjectStore(tasksIn = listOf(task.id))
        val withProject = CadenceRepository(
            taskStore,
            projectStore,
            sectionStore,
            tagStore,
            FakeBackupStore(),
            attachmentStore,
            blobStore,
        )

        withProject.deleteProject(id = "p1", deleteTasks = false)

        assertNotNull(blobStore.file(hash))
        assertEquals(1, attachmentStore.forTask(task.id).size)
    }

    @Test
    fun `a recurring task hands its attachments to the next occurrence unremoved`() = runTest {
        val task = store(recurring())
        val hash = attach(task.id)

        repository.setCompleted(task, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        val done = rowsTitled("Rat poison").single { it.isDone }
        assertEquals(1, attachmentStore.forTask(next.id).size)
        assertEquals(hash, attachmentStore.forTask(next.id).single().sha256)
        // The finished occurrence keeps its own copy — carrying over means cloning, not moving.
        assertEquals(1, attachmentStore.forTask(done.id).size)
        assertNotNull(blobStore.file(hash))
    }

    @Test
    fun `deleting one attachment reclaims its blob but leaves the task's other attachments`() =
        runTest {
            val task = store(Task(title = "Send the invoice"))
            attach(task.id, byteArrayOf(1))
            val secondHash = attach(task.id, byteArrayOf(2))
            val removedId = attachmentStore.forTask(task.id).first().id

            repository.deleteAttachment(removedId)

            assertEquals(1, attachmentStore.forTask(task.id).size)
            assertNotNull(blobStore.file(secondHash))
        }

    // ── Manual order ───────────────────────────────────────────────────────────────
    //
    // `sortOrder` was in every table from the first schema and nothing wrote it: every row was
    // inserted at 0, so "manual" order was id order. These pin what writes it now.

    @Test
    fun `reorderTasks renumbers densely, in the order it was handed`() = runTest {
        val a = store(Task(title = "A", sortOrder = 0))
        val b = store(Task(title = "B", sortOrder = 1))
        val c = store(Task(title = "C", sortOrder = 2))

        repository.reorderTasks(listOf(c.id, a.id, b.id))

        assertEquals(0, taskStore.row(c.id).sortOrder)
        assertEquals(1, taskStore.row(a.id).sortOrder)
        assertEquals(2, taskStore.row(b.id).sortOrder)
    }

    @Test
    fun `reorderTasks leaves a row that did not move unwritten`() = runTest {
        val stale = Instant.parse("2026-01-01T00:00:00Z")
        val a = store(Task(title = "A", sortOrder = 0, updatedAt = stale))
        val b = store(Task(title = "B", sortOrder = 1, updatedAt = stale))
        val c = store(Task(title = "C", sortOrder = 2, updatedAt = stale))

        // B and C swap; A stays where it was.
        repository.reorderTasks(listOf(a.id, c.id, b.id))

        // The push reads `updatedAt`, so an untouched row must keep its old one or a drag ships
        // the whole list to the other device.
        assertEquals(stale, taskStore.row(a.id).updatedAt)
        assertTrue(taskStore.row(b.id).updatedAt.isAfter(stale))
        assertTrue(taskStore.row(c.id).updatedAt.isAfter(stale))
    }

    @Test
    fun `reorderTasks skips an id no row answers to`() = runTest {
        val a = store(Task(title = "A", sortOrder = 0))
        val b = store(Task(title = "B", sortOrder = 1))

        // A row deleted by a pull while the list was being dragged.
        repository.reorderTasks(listOf(b.id, "ghost", a.id))

        assertEquals(0, taskStore.row(b.id).sortOrder)
        assertEquals(2, taskStore.row(a.id).sortOrder)
    }

    @Test
    fun `a new task lands at the bottom of its list, not the top`() = runTest {
        store(Task(title = "First", projectId = "p1", sortOrder = 0))
        store(Task(title = "Second", projectId = "p1", sortOrder = 1))

        val id = repository.upsertTask(Task(title = "Third", projectId = "p1"))

        assertEquals(2, taskStore.row(id).sortOrder)
    }

    @Test
    fun `each list is numbered on its own — the Inbox does not follow a project`() = runTest {
        store(Task(title = "In a project", projectId = "p1", sortOrder = 7))

        val id = repository.upsertTask(Task(title = "In the Inbox"))

        assertEquals(0, taskStore.row(id).sortOrder)
    }

    @Test
    fun `reorderProjects ignores an id from another parent`() = runTest {
        projectStore.insert(Project(id = "root-a", name = "A", sortOrder = 0))
        projectStore.insert(Project(id = "root-b", name = "B", sortOrder = 1))
        projectStore.insert(Project(id = "child", name = "C", parentId = "root-a", sortOrder = 0))

        repository.reorderProjects(parentId = null, orderedIds = listOf("root-b", "child", "root-a"))

        assertEquals(0, projectStore.row("root-b").sortOrder)
        // Ordered *after* the ignored id, so the caller's relative order survives the filter.
        assertEquals(1, projectStore.row("root-a").sortOrder)
        // A subproject dragged into the root list is a move, not a reorder — untouched here.
        assertEquals(0, projectStore.row("child").sortOrder)
    }

    @Test
    fun `a new project lands last among its siblings`() = runTest {
        projectStore.insert(Project(id = "root-a", name = "A", sortOrder = 0))
        projectStore.insert(Project(id = "root-b", name = "B", sortOrder = 1))

        val result = repository.upsertProject(Project(name = "C"))

        val id = (result as RepositoryResult.Success).data
        assertEquals(2, projectStore.row(id).sortOrder)
    }

    @Test
    fun `reorderSections renumbers one project's bands and leaves another project alone`() = runTest {
        sectionStore.insert(Section(id = "s1", projectId = "p1", name = "One", sortOrder = 0))
        sectionStore.insert(Section(id = "s2", projectId = "p1", name = "Two", sortOrder = 1))
        sectionStore.insert(Section(id = "other", projectId = "p2", name = "Other", sortOrder = 0))

        repository.reorderSections(projectId = "p1", orderedIds = listOf("s2", "other", "s1"))

        assertEquals(0, sectionStore.row("s2").sortOrder)
        assertEquals(1, sectionStore.row("s1").sortOrder)
        assertEquals(0, sectionStore.row("other").sortOrder)
    }

    @Test
    fun `a new section lands below the bands the project already has`() = runTest {
        sectionStore.insert(Section(id = "s1", projectId = "p1", name = "One", sortOrder = 0))

        val result = repository.upsertSection(Section(projectId = "p1", name = "Two"))

        val id = (result as RepositoryResult.Success).data
        assertEquals(1, sectionStore.row(id).sortOrder)
    }

    // ── Tags ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a new tag is minted an id and lands at the bottom of the list`() = runTest {
        val first = repository.upsertTag(Tag(name = "Errand")) as RepositoryResult.Success
        val second = repository.upsertTag(Tag(name = "Waiting")) as RepositoryResult.Success

        assertTrue(first.data.isNotBlank())
        assertEquals(0, tagStore.row(first.data).sortOrder)
        assertEquals(1, tagStore.row(second.data).sortOrder)
    }

    /**
     * The one validation a tag has beyond a name, and the reason it exists: `@home` in the
     * quick-add line has to mean one thing.
     */
    @Test
    fun `a second tag with the same name, in any case, is refused`() = runTest {
        repository.upsertTag(Tag(name = "Errand"))

        val clash = repository.upsertTag(Tag(name = "  errand  "))

        assertTrue(clash is RepositoryResult.Error)
        assertEquals(1, tagStore.rows().size)
    }

    /** Renaming is not a clash with itself. */
    @Test
    fun `recolouring a tag under its own name is allowed`() = runTest {
        val id = (repository.upsertTag(Tag(name = "Errand")) as RepositoryResult.Success).data

        val again = repository.upsertTag(tagStore.row(id).copy(colorHex = "#BA1A1A"))

        assertTrue(again is RepositoryResult.Success)
        assertEquals("#BA1A1A", tagStore.row(id).colorHex)
    }

    @Test
    fun `a blank name is a validation error`() = runTest {
        assertEquals(RepositoryResult.ValidationError, repository.upsertTag(Tag(name = "   ")))
    }

    /**
     * The whole point of the packed column: deleting a tag is one row.
     *
     * If this ever starts failing because the tasks were rewritten, the "one row per delete"
     * property is gone and a tag on two thousand tasks costs two thousand rows on the next push.
     */
    @Test
    fun `deleting a tag writes one row and leaves every task alone`() = runTest {
        val id = (repository.upsertTag(Tag(name = "Errand")) as RepositoryResult.Success).data
        val task = store(Task(title = "Post the parcel", tagIds = listOf(id)))
        val before = taskStore.row(task.id).updatedAt

        repository.deleteTag(id)

        assertTrue(tagStore.rows().isEmpty())
        assertEquals(listOf(id), taskStore.row(task.id).tagIds)
        assertEquals(before, taskStore.row(task.id).updatedAt)
    }

    @Test
    fun `setTaskTags dedupes, drops blanks and does not write an unchanged list`() = runTest {
        val task = store(Task(title = "Post the parcel", tagIds = listOf("a")))
        val before = taskStore.row(task.id).updatedAt

        repository.setTaskTags(taskStore.row(task.id), listOf("a"))
        assertEquals(before, taskStore.row(task.id).updatedAt)

        repository.setTaskTags(taskStore.row(task.id), listOf("a", "", "b", "a"))
        assertEquals(listOf("a", "b"), taskStore.row(task.id).tagIds)
    }

    /**
     * Kept, not pruned: a pull can deliver a task before the tag it names, and pruning on write
     * would erase the label a moment before its tag arrived. Dropping a dangling id is the *read*
     * side's job (`CadenceUiState.tagsOf`).
     */
    @Test
    fun `setTaskTags keeps an id no tag answers to`() = runTest {
        val task = store(Task(title = "Post the parcel"))

        repository.setTaskTags(task, listOf("not-a-tag-yet"))

        assertEquals(listOf("not-a-tag-yet"), taskStore.row(task.id).tagIds)
    }

    @Test
    fun `toggling adds a tag the task lacks and removes one it has`() = runTest {
        val task = store(Task(title = "Post the parcel"))

        repository.toggleTaskTag(taskStore.row(task.id), "a")
        assertEquals(listOf("a"), taskStore.row(task.id).tagIds)

        repository.toggleTaskTag(taskStore.row(task.id), "a")
        assertEquals(emptyList<String>(), taskStore.row(task.id).tagIds)
    }

    /** A label on the parent says nothing about its checklist — unlike a project or a section,
     *  which the steps do follow. */
    @Test
    fun `tagging a parent leaves its subtasks untagged`() = runTest {
        val parent = store(Task(title = "Move house"))
        repository.addSubtask(parent, "Book the van")
        val step = taskStore.rows().single { it.parentId == parent.id }

        repository.setTaskTags(parent, listOf("a"))

        assertEquals(emptyList<String>(), taskStore.row(step.id).tagIds)
    }

    /** A recurring task's labels are part of the work, so the next occurrence inherits them —
     *  the same rule the checklist and the attachments follow. */
    @Test
    fun `the next occurrence of a recurring task keeps its tags`() = runTest {
        val task = store(recurring().copy(tagIds = listOf("a", "b")))

        repository.setCompleted(task, true, today)

        val next = rowsTitled("Rat poison").single { !it.isDone }
        assertEquals(listOf("a", "b"), next.tagIds)
    }

    /** The danger zone wipes tags too, as tombstones like everything else — a wipe that left the
     *  labels behind would hand them straight back on the next pull. */
    @Test
    fun `the danger zone tombstones every tag`() = runTest {
        repository.upsertTag(Tag(name = "Errand"))

        repository.deleteEverything()

        assertTrue(tagStore.rows().isEmpty())
    }
}

/** An in-memory [TaskStore] with SQLite's semantics for the guarded writes. */
private class FakeTaskStore : TaskStore {

    private val table = MutableStateFlow<Map<String, Task>>(emptyMap())
    private var nextId = 1

    /**
     * Test-only setup helper: mints an id when [task] does not carry one, the way
     * [de.andi1984.cadence.data.CadenceRepository] does for a real insert — [TaskStore.insert]
     * itself now only ever receives a task that already has its final id.
     */
    fun put(task: Task): String {
        val id = task.id.ifBlank { "fake-task-${nextId++}" }
        table.value = table.value + (id to task.copy(id = id))
        return id
    }

    /** The raw row, tombstone included — how a test asserts that a delete stamped rather than removed. */
    fun row(id: String): Task = table.value[id] ?: error("no task with id $id")

    /** What the app can see. Every read below goes through this, as `deletedAt IS NULL` does in SQL. */
    fun rows(): List<Task> = table.value.values.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Task>> = table.map { m -> m.values.filter { it.deletedAt == null } }

    override fun observeById(id: String): Flow<Task?> = table.map { m -> m[id]?.takeIf { it.deletedAt == null } }

    override suspend fun getAll(): List<Task> = rows()

    override suspend fun byId(id: String): Task? = table.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun subtasksOf(parentId: String): List<Task> = rows()
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(task: Task) {
        put(task)
    }

    override suspend fun update(task: Task) {
        if (table.value.containsKey(task.id)) table.value = table.value + (task.id to task)
    }

    override suspend fun tombstoneWithSubtasks(id: String, at: Instant) {
        // Models the store faithfully: the row stays, stamped, and every read below hides it —
        // a fake that removed the row would let a tombstone bug through unnoticed.
        table.value = table.value.mapValues { (_, task) ->
            if ((task.id == id || task.parentId == id) && task.deletedAt == null) {
                task.copy(deletedAt = at, updatedAt = at)
            } else {
                task
            }
        }
    }

    override suspend fun tombstoneAll(at: Instant) {
        table.value = table.value.mapValues { (_, task) ->
            if (task.deletedAt == null) task.copy(deletedAt = at, updatedAt = at) else task
        }
    }

    override suspend fun completeIfOpen(id: String, completedAt: Instant): Int {
        val task = table.value[id]?.takeIf { it.deletedAt == null } ?: return 0
        if (task.completedAt != null) return 0
        table.value = table.value + (id to task.copy(completedAt = completedAt, updatedAt = completedAt))
        return 1
    }

    override suspend fun reopenIfDone(id: String, updatedAt: Instant): Int {
        val task = table.value[id]?.takeIf { it.deletedAt == null } ?: return 0
        if (task.completedAt == null) return 0
        table.value = table.value + (id to task.copy(completedAt = null, updatedAt = updatedAt))
        return 1
    }

    /** `Section.sq`'s `clearTasksIn`, which the store runs beside the section's own tombstone. */
    fun clearSection(sectionId: String, at: Instant) {
        table.value = table.value.mapValues { (_, task) ->
            if (task.sectionId == sectionId && task.deletedAt == null) {
                task.copy(sectionId = null, updatedAt = at)
            } else {
                task
            }
        }
    }

    override suspend fun openSuccessorsOf(id: String): List<String> = rows()
        .filter { it.spawnedFromId == id && it.completedAt == null }
        .map { it.id }

    /**
     * `Task.sq`'s `updateSortOrder`, guard included: a row whose position is already the one it
     * would be given is *not* written. A fake that restamped it would hide the rule the sync push
     * depends on — that a drag puts the rows that moved on the wire, and nothing else.
     */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        var next = table.value
        orders.forEach { (id, position) ->
            val task = next[id]?.takeIf { it.deletedAt == null } ?: return@forEach
            if (task.sortOrder == position) return@forEach
            next = next + (id to task.copy(sortOrder = position, updatedAt = at))
        }
        table.value = next
    }

    override suspend fun maxSortOrder(projectId: String?): Int? = rows()
        .filter { it.parentId == null && it.projectId == projectId }
        .maxOfOrNull { it.sortOrder }
}

/**
 * An in-memory [ProjectStore].
 *
 * It used to answer `emptyList()` to everything and exist only to satisfy the constructor. It
 * holds rows now because manual order is a fact about a *list* — "the projects under this parent"
 * — and a store with no rows cannot tell one bucket from another.
 */
private class FakeProjectStore(private val tasksIn: List<String> = emptyList()) : ProjectStore {

    private val table = MutableStateFlow<Map<String, Project>>(emptyMap())

    fun rows(): List<Project> = table.value.values.filter { it.deletedAt == null }

    fun row(id: String): Project = table.value[id] ?: error("no project with id $id")

    override fun observeAll(): Flow<List<Project>> =
        table.map { m -> m.values.filter { it.deletedAt == null } }

    override suspend fun getAll(): List<Project> = rows()

    override suspend fun insert(project: Project) {
        table.value = table.value + (project.id to project)
    }

    override suspend fun update(project: Project) = insert(project)

    override suspend fun taskIdsIn(id: String): List<String> = tasksIn

    override suspend fun tombstoneWithChildren(id: String, deleteTasks: Boolean, at: Instant) {
        table.value = table.value.mapValues { (_, project) ->
            if ((project.id == id || project.parentId == id) && project.deletedAt == null) {
                project.copy(deletedAt = at, updatedAt = at)
            } else {
                project
            }
        }
    }

    /** Records the wipe so a test can assert the repository reached both tables, not just one. */
    var wipedAt: Instant? = null
        private set

    override suspend fun tombstoneAll(at: Instant) {
        wipedAt = at
        table.value = table.value.mapValues { (_, project) ->
            if (project.deletedAt == null) project.copy(deletedAt = at, updatedAt = at) else project
        }
    }

    /** See [FakeTaskStore.reorder] for why the unchanged rows are left alone. */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        var next = table.value
        orders.forEach { (id, position) ->
            val project = next[id]?.takeIf { it.deletedAt == null } ?: return@forEach
            if (project.sortOrder == position) return@forEach
            next = next + (id to project.copy(sortOrder = position, updatedAt = at))
        }
        table.value = next
    }

    override suspend fun maxSortOrder(parentId: String?): Int? = rows()
        .filter { it.parentId == parentId }
        .maxOfOrNull { it.sortOrder }
}

private class FakeBackupStore : BackupStore {

    override suspend fun mergeAll(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        revivedAt: Instant,
    ) = Unit
}

/**
 * An in-memory [TagStore].
 *
 * Shorter than [FakeSectionStore] by exactly the thing that makes a tag a tag: `tombstone` writes
 * one row and touches no task, because membership lives on the other side of the link.
 */
private class FakeTagStore : TagStore {

    private val table = MutableStateFlow<Map<String, Tag>>(emptyMap())

    fun row(id: String): Tag = table.value[id] ?: error("no tag with id $id")

    fun rows(): List<Tag> = table.value.values.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Tag>> =
        table.map { m -> m.values.filter { it.deletedAt == null }.sortedBy { it.sortOrder } }

    override suspend fun getAll(): List<Tag> = rows().sortedBy { it.sortOrder }

    override suspend fun insert(tag: Tag) {
        table.value = table.value + (tag.id to tag)
    }

    override suspend fun update(tag: Tag) {
        table.value = table.value + (tag.id to tag)
    }

    override suspend fun tombstone(id: String, at: Instant) {
        val tag = table.value[id] ?: return
        if (tag.deletedAt != null) return
        table.value = table.value + (id to tag.copy(deletedAt = at, updatedAt = at))
    }

    override suspend fun tombstoneAll(at: Instant) {
        table.value = table.value.mapValues { (_, tag) ->
            if (tag.deletedAt == null) tag.copy(deletedAt = at, updatedAt = at) else tag
        }
    }

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        var next = table.value
        orders.forEach { (id, position) ->
            val tag = next[id]?.takeIf { it.deletedAt == null } ?: return@forEach
            if (tag.sortOrder == position) return@forEach
            next = next + (id to tag.copy(sortOrder = position, updatedAt = at))
        }
        table.value = next
    }

    override suspend fun maxSortOrder(): Int? = rows().maxOfOrNull { it.sortOrder }
}

/**
 * An in-memory [SectionStore], holding a [FakeTaskStore] because the real one does too.
 *
 * `tombstone` is two writes in one transaction in SQL — stamp the row, free its tasks — and a fake
 * that only stamped would let "deleting a section leaves its tasks pointing at a heading nothing
 * answers to" through unnoticed.
 */
private class FakeSectionStore(private val tasks: FakeTaskStore) : SectionStore {

    private val table = MutableStateFlow<Map<String, Section>>(emptyMap())

    /** The raw row, tombstone included — how a test asserts a delete stamped rather than removed. */
    fun row(id: String): Section = table.value[id] ?: error("no section with id $id")

    fun rows(): List<Section> = table.value.values.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Section>> =
        table.map { m -> m.values.filter { it.deletedAt == null } }

    override suspend fun getAll(): List<Section> = rows()

    override suspend fun insert(section: Section) {
        table.value = table.value + (section.id to section)
    }

    override suspend fun update(section: Section) {
        table.value = table.value + (section.id to section)
    }

    override suspend fun tombstone(id: String, at: Instant) {
        tasks.clearSection(id, at)
        val section = table.value[id] ?: return
        if (section.deletedAt != null) return
        table.value = table.value + (id to section.copy(deletedAt = at, updatedAt = at))
    }

    override suspend fun tombstoneAll(at: Instant) {
        table.value = table.value.mapValues { (_, section) ->
            if (section.deletedAt == null) section.copy(deletedAt = at, updatedAt = at) else section
        }
    }

    /** See [FakeTaskStore.reorder] for why the unchanged rows are left alone. */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        var next = table.value
        orders.forEach { (id, position) ->
            val section = next[id]?.takeIf { it.deletedAt == null } ?: return@forEach
            if (section.sortOrder == position) return@forEach
            next = next + (id to section.copy(sortOrder = position, updatedAt = at))
        }
        table.value = next
    }

    override suspend fun maxSortOrder(projectId: String): Int? = rows()
        .filter { it.projectId == projectId }
        .maxOfOrNull { it.sortOrder }
}

/** An in-memory [AttachmentStore] mirroring [FakeTaskStore]'s shape. */
private class FakeAttachmentStore : AttachmentStore {

    private val table = MutableStateFlow<Map<String, Attachment>>(emptyMap())
    private var nextId = 1

    fun put(attachment: Attachment): String {
        val id = attachment.id.ifBlank { "fake-attachment-${nextId++}" }
        table.value = table.value + (id to attachment.copy(id = id))
        return id
    }

    override fun observeAll(): Flow<List<Attachment>> = table.map { it.values.toList() }

    override suspend fun byId(id: String): Attachment? = table.value[id]

    override suspend fun forTask(taskId: String): List<Attachment> = table.value.values
        .filter { it.taskId == taskId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(attachment: Attachment) {
        put(attachment)
    }

    override suspend fun delete(id: String) {
        table.value = table.value - id
    }

    override suspend fun deleteForTasks(taskIds: List<String>) {
        table.value = table.value.filterValues { it.taskId !in taskIds }
    }

    override suspend fun hashesForTasks(taskIds: List<String>): List<String> = table.value.values
        .filter { it.taskId in taskIds }
        .mapNotNull { it.sha256 }
        .distinct()

    override suspend fun stillReferenced(hashes: List<String>): List<String> {
        val named = table.value.values.mapNotNull { it.sha256 }.toSet()
        return hashes.filter { it in named }
    }

    override suspend fun referencedHashes(): List<String> =
        table.value.values.mapNotNull { it.sha256 }.distinct()
}
