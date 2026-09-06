package de.andi1984.cadence

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.MAX_ATTACHMENTS_PER_TASK
import de.andi1984.cadence.data.RepositoryResult
import de.andi1984.cadence.data.StoreResult
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate

/**
 * Completion is where recurrence turns into rows, so this is where a duplicate can be created.
 * The rows the UI hands back are snapshots, and the same open snapshot arrives again on a second
 * tap — every test here works with a deliberately stale [Task].
 *
 * The repository runs over the real SQLDelight stores on an in-memory database ([TestStores]),
 * not over fakes: the rules under test are the repository's, but half of them lean on what the
 * store guarantees — `completeIfOpen` reporting 0 the second time, a tombstone hiding from every
 * read — and a fake that restated those guarantees was a second copy of the contract that
 * nothing pinned.
 */
class CadenceRepositoryTest {

    private val fortnightly = RecurrenceRule(interval = 2, unit = RecurrenceUnit.WEEK)
    private val today = LocalDate.of(2026, 8, 7)

    private val stores = TestStores()
    private val taskStore = stores.taskStore
    private val projectStore = stores.projectStore
    private val sectionStore = stores.sectionStore
    private val tagStore = stores.tagStore
    private val attachmentStore = stores.attachmentStore
    private val blobStore = stores.blobStore
    private val repository = stores.repository()

    private var nextId = 1

    /**
     * Inserts [task] — minting an id when it carries none, as the repository does for a real
     * insert — and returns the row **as stored**: timestamps come back truncated to millis, so a
     * later "was this row written?" comparison has to start from what the database holds.
     */
    private suspend fun store(task: Task): Task {
        val id = task.id.ifBlank { "task-${nextId++}" }
        taskStore.insert(task.copy(id = id))
        return taskRow(id)
    }

    /** The raw row, tombstone included — how a test asserts a delete stamped rather than removed. */
    private suspend fun taskRow(id: String): Task = stores.taskRow(id)

    /** Stores [bytes] as a FILE attachment on [taskId] and returns the hash it landed at. */
    private suspend fun attach(taskId: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): String {
        val result = blobStore.store(ByteArrayInputStream(bytes), maxBytes = 1024) as StoreResult.Ok
        attachmentStore.insert(
            Attachment(
                id = "attachment-${nextId++}",
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

    private suspend fun rowsTitled(title: String) = taskStore.getAll().filter { it.title == title }

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
        taskStore.update(edited)

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
        assertNotNull(stores.sectionRow(sectionId).deletedAt)
        assertTrue(sectionStore.getAll().isEmpty())
        val freed = taskRow(task.id)
        assertNull(freed.sectionId)
        assertEquals("p1", freed.projectId)
        assertNull(freed.deletedAt)
    }

    @Test
    fun `deleting a section twice leaves the first tombstone's timestamp alone`() = runTest {
        val sectionId = section()

        repository.deleteSection(sectionId)
        val stamped = stores.sectionRow(sectionId).deletedAt
        repository.deleteSection(sectionId)

        assertEquals(stamped, stores.sectionRow(sectionId).deletedAt)
    }

    @Test
    fun `moving a task to another project drops the heading it had`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice", projectId = "p1", sectionId = sectionId))
        val step = store(Task(title = "Find the receipt", parentId = task.id, projectId = "p1", sectionId = sectionId))

        repository.moveToProject(taskRow(task.id), "p2")

        // A section belongs to one project, so the heading means nothing in the new one — and the
        // checklist follows its task rather than staying behind under a band it no longer shares.
        assertEquals("p2", taskRow(task.id).projectId)
        assertNull(taskRow(task.id).sectionId)
        assertEquals("p2", taskRow(step.id).projectId)
        assertNull(taskRow(step.id).sectionId)
    }

    @Test
    fun `filing a task under a section takes its checklist with it`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice", projectId = "p1"))
        val step = store(Task(title = "Find the receipt", parentId = task.id, projectId = "p1"))

        repository.moveToSection(taskRow(task.id), sectionId)

        assertEquals(sectionId, taskRow(task.id).sectionId)
        assertEquals(sectionId, taskRow(step.id).sectionId)
    }

    @Test
    fun `a task with no project cannot be filed under a section`() = runTest {
        val sectionId = section()
        val task = store(Task(title = "Send the invoice"))

        repository.moveToSection(taskRow(task.id), sectionId)

        // There is nowhere for it to be grouped: the Inbox draws no headings.
        assertNull(taskRow(task.id).sectionId)
    }

    @Test
    fun `a section with a blank name is refused rather than stored`() = runTest {
        val result = repository.upsertSection(Section(projectId = "p1", name = "   "))

        assertTrue(result is RepositoryResult.ValidationError)
        assertTrue(sectionStore.getAll().isEmpty())
    }

    @Test
    fun `the danger zone tombstones the sections too`() = runTest {
        val sectionId = section()

        repository.deleteEverything()

        assertNotNull(stores.sectionRow(sectionId).deletedAt)
        assertTrue(sectionStore.getAll().isEmpty())
    }

    // ── Attachments ────────────────────────────────────────────────────────────────

    @Test
    fun `the danger zone tombstones every task and project rather than removing rows`() = runTest {
        projectStore.insert(Project(id = "p1", name = "Bills"))
        val first = store(Task(title = "Send the invoice", projectId = "p1"))
        val second = store(Task(title = "Book flights", parentId = first.id, projectId = "p1"))

        val wiped = repository.deleteEverything()

        assertEquals(setOf(first.id, second.id), wiped.toSet())
        assertTrue(taskStore.getAll().isEmpty())
        assertTrue(projectStore.getAll().isEmpty())
        // A hard delete would look identical to the two lines above and lose the deletion on sync.
        assertNotNull(taskRow(first.id).deletedAt)
        assertNotNull(taskRow(second.id).deletedAt)
        assertNotNull(stores.projectRow("p1").deletedAt)
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
        val stamped = taskRow(task.id).deletedAt
        repository.deleteEverything()

        assertEquals(stamped, taskRow(task.id).deletedAt)
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
        projectStore.insert(Project(id = "p1", name = "Bills"))
        val task = store(Task(title = "Send the invoice", projectId = "p1"))
        val hash = attach(task.id)

        repository.deleteProject(id = "p1", deleteTasks = true)

        assertNotNull(taskRow(task.id).deletedAt)
        assertTrue(attachmentStore.forTask(task.id).isEmpty())
        assertNull(blobStore.file(hash))
    }

    @Test
    fun `moving a project's tasks to the Inbox keeps their attachments`() = runTest {
        projectStore.insert(Project(id = "p1", name = "Bills"))
        val task = store(Task(title = "Send the invoice", projectId = "p1"))
        val hash = attach(task.id)

        repository.deleteProject(id = "p1", deleteTasks = false)

        assertNull(taskRow(task.id).projectId)
        assertNull(taskRow(task.id).deletedAt)
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

    @Test
    fun `the attachment index reports a blob on disk as present and a missing one as absent`() =
        runTest {
            val task = store(Task(title = "Send the invoice"))
            val kept = attach(task.id, byteArrayOf(1))
            val lost = attach(task.id, byteArrayOf(2))
            // What a backup restored without its blobs, or a second device, leaves behind: the
            // row is still there and the bytes are not.
            blobStore.deleteAll(listOf(lost))

            val index = repository.attachmentIndex.first()

            assertEquals(2, index.attachments.size)
            assertEquals(setOf(kept), index.presentBlobs)
        }

    @Test
    fun `a link is in the index with no hash to be present or missing`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        repository.addLinkAttachment(task.id, "https://example.org/receipt", "Receipt")

        val index = repository.attachmentIndex.first()

        assertEquals(AttachmentKind.LINK, index.attachments.single().kind)
        assertTrue(index.presentBlobs.isEmpty())
    }

    @Test
    fun `relocating heals the row in place and reclaims the hash it no longer names`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val original = attach(task.id, byteArrayOf(1))
        val id = attachmentStore.forTask(task.id).single().id
        blobStore.deleteAll(listOf(original))

        // Different bytes on purpose: someone re-exporting the same invoice gets a different
        // file with the same meaning, and refusing it would leave the row broken forever.
        val result = repository.relocateAttachment(id, ByteArrayInputStream(byteArrayOf(9, 9)))

        assertTrue(result is CadenceRepository.AddAttachmentResult.Success)
        val healed = attachmentStore.forTask(task.id).single()
        assertEquals(id, healed.id)
        assertEquals("file.bin", healed.name)
        assertEquals(2L, healed.sizeBytes)
        assertNotNull(healed.sha256?.let { blobStore.file(it) })
        assertNull(blobStore.file(original))
    }

    @Test
    fun `relocating a blob another attachment still names leaves that blob alone`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        val shared = attach(task.id, byteArrayOf(1))
        attach(task.id, byteArrayOf(1))
        val id = attachmentStore.forTask(task.id).first().id

        repository.relocateAttachment(id, ByteArrayInputStream(byteArrayOf(9, 9)))

        // The second row is the refcount, read back rather than trusted from a counter.
        assertNotNull(blobStore.file(shared))
    }

    @Test
    fun `relocating a link is refused rather than turning it into a file`() = runTest {
        val task = store(Task(title = "Send the invoice"))
        repository.addLinkAttachment(task.id, "https://example.org", "Receipt")
        val id = attachmentStore.forTask(task.id).single().id

        val result = repository.relocateAttachment(id, ByteArrayInputStream(byteArrayOf(1)))

        assertEquals(CadenceRepository.AddAttachmentResult.ValidationError, result)
        assertEquals(AttachmentKind.LINK, attachmentStore.forTask(task.id).single().kind)
    }

    @Test
    fun `a task at the attachment limit refuses the next one rather than dropping an old one`() =
        runTest {
            val task = store(Task(title = "Send the invoice"))
            repeat(MAX_ATTACHMENTS_PER_TASK) { index -> attach(task.id, byteArrayOf(index.toByte())) }

            val result = repository.addLinkAttachment(task.id, "https://example.org", "One more")

            assertEquals(CadenceRepository.AddAttachmentResult.LimitReached, result)
            assertEquals(MAX_ATTACHMENTS_PER_TASK, attachmentStore.forTask(task.id).size)
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

        assertEquals(0, taskRow(c.id).sortOrder)
        assertEquals(1, taskRow(a.id).sortOrder)
        assertEquals(2, taskRow(b.id).sortOrder)
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
        assertEquals(stale, taskRow(a.id).updatedAt)
        assertTrue(taskRow(b.id).updatedAt.isAfter(stale))
        assertTrue(taskRow(c.id).updatedAt.isAfter(stale))
    }

    @Test
    fun `reorderTasks skips an id no row answers to`() = runTest {
        val a = store(Task(title = "A", sortOrder = 0))
        val b = store(Task(title = "B", sortOrder = 1))

        // A row deleted by a pull while the list was being dragged.
        repository.reorderTasks(listOf(b.id, "ghost", a.id))

        assertEquals(0, taskRow(b.id).sortOrder)
        assertEquals(2, taskRow(a.id).sortOrder)
    }

    @Test
    fun `a new task lands at the bottom of its list, not the top`() = runTest {
        store(Task(title = "First", projectId = "p1", sortOrder = 0))
        store(Task(title = "Second", projectId = "p1", sortOrder = 1))

        val id = repository.upsertTask(Task(title = "Third", projectId = "p1"))

        assertEquals(2, taskRow(id).sortOrder)
    }

    @Test
    fun `each list is numbered on its own — the Inbox does not follow a project`() = runTest {
        store(Task(title = "In a project", projectId = "p1", sortOrder = 7))

        val id = repository.upsertTask(Task(title = "In the Inbox"))

        assertEquals(0, taskRow(id).sortOrder)
    }

    @Test
    fun `reorderProjects ignores an id from another parent`() = runTest {
        projectStore.insert(Project(id = "root-a", name = "A", sortOrder = 0))
        projectStore.insert(Project(id = "root-b", name = "B", sortOrder = 1))
        projectStore.insert(Project(id = "child", name = "C", parentId = "root-a", sortOrder = 0))

        repository.reorderProjects(parentId = null, orderedIds = listOf("root-b", "child", "root-a"))

        assertEquals(0, stores.projectRow("root-b").sortOrder)
        // Ordered *after* the ignored id, so the caller's relative order survives the filter.
        assertEquals(1, stores.projectRow("root-a").sortOrder)
        // A subproject dragged into the root list is a move, not a reorder — untouched here.
        assertEquals(0, stores.projectRow("child").sortOrder)
    }

    @Test
    fun `a new project lands last among its siblings`() = runTest {
        projectStore.insert(Project(id = "root-a", name = "A", sortOrder = 0))
        projectStore.insert(Project(id = "root-b", name = "B", sortOrder = 1))

        val result = repository.upsertProject(Project(name = "C"))

        val id = (result as RepositoryResult.Success).data
        assertEquals(2, stores.projectRow(id).sortOrder)
    }

    @Test
    fun `reorderSections renumbers one project's bands and leaves another project alone`() = runTest {
        sectionStore.insert(Section(id = "s1", projectId = "p1", name = "One", sortOrder = 0))
        sectionStore.insert(Section(id = "s2", projectId = "p1", name = "Two", sortOrder = 1))
        sectionStore.insert(Section(id = "other", projectId = "p2", name = "Other", sortOrder = 0))

        repository.reorderSections(projectId = "p1", orderedIds = listOf("s2", "other", "s1"))

        assertEquals(0, stores.sectionRow("s2").sortOrder)
        assertEquals(1, stores.sectionRow("s1").sortOrder)
        assertEquals(0, stores.sectionRow("other").sortOrder)
    }

    @Test
    fun `a new section lands below the bands the project already has`() = runTest {
        sectionStore.insert(Section(id = "s1", projectId = "p1", name = "One", sortOrder = 0))

        val result = repository.upsertSection(Section(projectId = "p1", name = "Two"))

        val id = (result as RepositoryResult.Success).data
        assertEquals(1, stores.sectionRow(id).sortOrder)
    }

    // ── Tags ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a new tag is minted an id and lands at the bottom of the list`() = runTest {
        val first = repository.upsertTag(Tag(name = "Errand")) as RepositoryResult.Success
        val second = repository.upsertTag(Tag(name = "Waiting")) as RepositoryResult.Success

        assertTrue(first.data.isNotBlank())
        assertEquals(0, stores.tagRow(first.data).sortOrder)
        assertEquals(1, stores.tagRow(second.data).sortOrder)
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
        assertEquals(1, tagStore.getAll().size)
    }

    /** Renaming is not a clash with itself. */
    @Test
    fun `recolouring a tag under its own name is allowed`() = runTest {
        val id = (repository.upsertTag(Tag(name = "Errand")) as RepositoryResult.Success).data

        val again = repository.upsertTag(stores.tagRow(id).copy(colorHex = "#BA1A1A"))

        assertTrue(again is RepositoryResult.Success)
        assertEquals("#BA1A1A", stores.tagRow(id).colorHex)
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
        val before = taskRow(task.id).updatedAt

        repository.deleteTag(id)

        assertTrue(tagStore.getAll().isEmpty())
        assertEquals(listOf(id), taskRow(task.id).tagIds)
        assertEquals(before, taskRow(task.id).updatedAt)
    }

    @Test
    fun `setTaskTags dedupes, drops blanks and does not write an unchanged list`() = runTest {
        val task = store(Task(title = "Post the parcel", tagIds = listOf("a")))
        val before = taskRow(task.id).updatedAt

        repository.setTaskTags(taskRow(task.id), listOf("a"))
        assertEquals(before, taskRow(task.id).updatedAt)

        repository.setTaskTags(taskRow(task.id), listOf("a", "", "b", "a"))
        assertEquals(listOf("a", "b"), taskRow(task.id).tagIds)
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

        assertEquals(listOf("not-a-tag-yet"), taskRow(task.id).tagIds)
    }

    @Test
    fun `toggling adds a tag the task lacks and removes one it has`() = runTest {
        val task = store(Task(title = "Post the parcel"))

        repository.toggleTaskTag(taskRow(task.id), "a")
        assertEquals(listOf("a"), taskRow(task.id).tagIds)

        repository.toggleTaskTag(taskRow(task.id), "a")
        assertEquals(emptyList<String>(), taskRow(task.id).tagIds)
    }

    /** A label on the parent says nothing about its checklist — unlike a project or a section,
     *  which the steps do follow. */
    @Test
    fun `tagging a parent leaves its subtasks untagged`() = runTest {
        val parent = store(Task(title = "Move house"))
        repository.addSubtask(parent, "Book the van")
        val step = taskStore.getAll().single { it.parentId == parent.id }

        repository.setTaskTags(parent, listOf("a"))

        assertEquals(emptyList<String>(), taskRow(step.id).tagIds)
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

        assertTrue(tagStore.getAll().isEmpty())
    }
}
