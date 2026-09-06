package de.andi1984.cadence.ui

import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.ui.dnd.DropIntent
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.PickedFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The rest of the ViewModel's public surface, beside the undo state machine
 * [CadenceViewModelUndoTest] already covers: plain task/project/section CRUD, quick-add and
 * backup. Same arrangement as that file for the same reason — a real [CadenceRepository] over
 * the real SQLDelight stores on an in-memory database ([TestStores]), with only the platform
 * ports faked; `jvmTest` because constructing a real [CadenceSyncEngine] needs no Android
 * runtime that this compilation lacks.
 */
class CadenceViewModelCrudTest {

    private val stores = TestStores()
    private val taskStore = stores.taskStore
    private val tagStore = stores.tagStore
    private val repository = stores.repository()
    private val reminders = RecordingReminderScheduler()
    private val settings = FakeSettingsStore()
    private val backupGateway = FakeBackupGateway()
    private val attachmentOpener = RecordingAttachmentOpener()

    private fun TestScope.viewModel(): CadenceViewModel {
        val viewModel = CadenceViewModel(
            repository = repository,
            settingsStore = settings,
            reminderScheduler = reminders,
            backupGateway = backupGateway,
            attachmentOpener = attachmentOpener,
            syncEngine = CadenceSyncEngine(FakeSyncStore(), backgroundScope),
            scope = backgroundScope,
        )
        backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        return viewModel
    }

    private fun task(
        id: String,
        title: String = id,
        projectId: String? = null,
        due: LocalDate? = null,
    ) = Task(id = id, title = title, projectId = projectId, dueDate = due)

    /** The live row, as the database holds it now. */
    private suspend fun stored(id: String) = taskStore.byId(id) ?: error("no live task with id $id")

    // ── Tasks ────────────────────────────────────────────────────────────────────────

    @Test
    fun `toggling a task marks it done and toggling again reopens it`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.toggleTask(task("t1"))
        runCurrent()
        assertTrue(stored("t1").isDone)

        viewModel.toggleTask(stored("t1"))
        runCurrent()
        assertTrue(!stored("t1").isDone)
    }

    @Test
    fun `saving a task writes the edit straight through`() = runTest {
        stores.seedTasks(task("t1", title = "Old title"))
        val viewModel = viewModel()

        viewModel.saveTask(task("t1", title = "New title"))
        runCurrent()

        assertEquals("New title", stored("t1").title)
    }

    @Test
    fun `adding a subtask files it under the parent with the next sort order`() = runTest {
        // One existing step under the parent, so the new one lands at sortOrder 1.
        stores.seedTasks(task("parent"), task("step0").copy(parentId = "parent", sortOrder = 0))
        val viewModel = viewModel()

        viewModel.addSubtask(task("parent"), "  Buy milk  ")
        runCurrent()

        val added = taskStore.getAll().first { it.parentId == "parent" && it.title == "Buy milk" }
        assertEquals("parent", added.parentId)
        assertEquals(1, added.sortOrder)
        assertNull(viewModel.state.value.snackbarMessage)
    }

    @Test
    fun `adding a subtask with a blank title shows a validation snackbar and writes nothing`() = runTest {
        stores.seedTasks(task("parent"))
        val viewModel = viewModel()

        viewModel.addSubtask(task("parent"), "   ")
        runCurrent()

        assertEquals(1, taskStore.getAll().size)
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `setPriority updates the task's priority`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.setPriority(task("t1"), Priority.P1)
        runCurrent()

        assertEquals(Priority.P1, stored("t1").priority)
    }

    @Test
    fun `setDueDate updates the task's due date`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        val due = LocalDate.of(2026, 9, 1)

        viewModel.setDueDate(task("t1"), due)
        runCurrent()

        assertEquals(due, stored("t1").dueDate)
    }

    @Test
    fun `setDueTime updates the task's due time`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.setDueTime(task("t1"), LocalTime.of(14, 30))
        runCurrent()

        assertEquals(LocalTime.of(14, 30), stored("t1").dueTime)
    }

    @Test
    fun `setReminder updates the task's reminder time`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.setReminder(task("t1"), LocalTime.of(9, 0))
        runCurrent()

        assertEquals(LocalTime.of(9, 0), stored("t1").reminderTime)
    }

    @Test
    fun `setRecurrence updates the task's recurrence rule`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        val rule = RecurrenceRule(interval = 2, unit = RecurrenceUnit.DAY)

        viewModel.setRecurrence(task("t1"), rule)
        runCurrent()

        assertEquals(rule, stored("t1").recurrence)
    }

    @Test
    fun `setProject moves a task to another project`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.setProject(task("t1"), "work")
        runCurrent()

        assertEquals("work", stored("t1").projectId)
    }

    @Test
    fun `setSection files a task under a section of its project`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedSections(Section(id = "s1", projectId = "work", name = "This week"))
        stores.seedTasks(task("t1", projectId = "work"))
        val viewModel = viewModel()

        viewModel.setSection(task("t1", projectId = "work"), "s1")
        runCurrent()

        assertEquals("s1", stored("t1").sectionId)
    }

    @Test
    fun `snooze shifts the due date forward by the given number of days`() = runTest {
        stores.seedTasks(task("t1", due = LocalDate.of(2999, 1, 1)))
        val viewModel = viewModel()

        viewModel.snooze(task("t1", due = LocalDate.of(2999, 1, 1)), days = 3)
        runCurrent()

        assertEquals(LocalDate.of(2999, 1, 4), stored("t1").dueDate)
    }

    @Test
    fun `rescheduleOverdue moves every overdue task to today`() = runTest {
        stores.seedTasks(
            task("overdue", due = LocalDate.of(2000, 1, 1)),
            task("future", due = LocalDate.of(2999, 1, 1)),
            task("undated"),
        )
        val viewModel = viewModel()
        val today = LocalDate.now()

        viewModel.rescheduleOverdue()
        runCurrent()

        assertEquals(today, stored("overdue").dueDate)
        assertEquals(LocalDate.of(2999, 1, 1), stored("future").dueDate)
        assertNull(stored("undated").dueDate)
    }

    @Test
    fun `addParsedTask creates a task from the quick-add parse, falling back to the given project`() =
        runTest {
            val viewModel = viewModel()

            viewModel.addParsedTask(
                ParsedQuickAdd(title = "Water the plants", priority = Priority.P1),
                fallbackProjectId = "home",
            )
            runCurrent()

            val added = taskStore.getAll().single()
            assertEquals("Water the plants", added.title)
            assertEquals(Priority.P1, added.priority)
            assertEquals("home", added.projectId)
        }

    @Test
    fun `addParsedTask prefers the project the parse found over the fallback`() = runTest {
        val viewModel = viewModel()

        viewModel.addParsedTask(
            ParsedQuickAdd(title = "Pay rent", projectId = "finance"),
            fallbackProjectId = "home",
        )
        runCurrent()

        assertEquals("finance", taskStore.getAll().single().projectId)
    }

    @Test
    fun `addParsedTask does nothing for a blank title`() = runTest {
        val viewModel = viewModel()

        viewModel.addParsedTask(ParsedQuickAdd(title = "   "))
        runCurrent()

        assertTrue(taskStore.getAll().isEmpty())
    }

    // ── Projects ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addProject creates a project at the end of its siblings`() = runTest {
        stores.seedProjects(Project(id = "p1", name = "Existing", sortOrder = 0))
        val viewModel = viewModel()

        viewModel.addProject("New project", "#123456", null)
        runCurrent()

        val added = viewModel.state.value.projects.first { it.name == "New project" }
        assertEquals(1, added.sortOrder)
        assertEquals("#123456", added.colorHex)
    }

    @Test
    fun `addProject with a blank name shows a validation snackbar and creates nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.addProject("   ", "#123456", null)
        runCurrent()

        assertTrue(viewModel.state.value.projects.isEmpty())
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `editProject renames a project and recalculates its sort order when it moves to a new parent`() =
        runTest {
            stores.seedProjects(
                Project(id = "root", name = "Root"),
                Project(id = "child-a", name = "A", parentId = "root", sortOrder = 0),
                Project(id = "child-b", name = "B", parentId = "root", sortOrder = 1),
                Project(id = "other-root", name = "Other root"),
            )
            val viewModel = viewModel()

            viewModel.editProject(
                Project(id = "child-b", name = "B", parentId = "root", sortOrder = 1),
                name = "Renamed",
                colorHex = "#654321",
                parentId = "other-root",
            )
            runCurrent()

            val moved = viewModel.state.value.projects.first { it.id == "child-b" }
            assertEquals("Renamed", moved.name)
            assertEquals("other-root", moved.parentId)
            // No sibling under "other-root" yet, so the moved project lands at order 0.
            assertEquals(0, moved.sortOrder)
        }

    @Test
    fun `editProject with a blank name shows a validation snackbar and writes nothing`() = runTest {
        val original = Project(id = "p1", name = "Original")
        stores.seedProjects(original)
        val viewModel = viewModel()

        viewModel.editProject(original, name = " ", colorHex = "#000000", parentId = null)
        runCurrent()

        assertEquals("Original", viewModel.state.value.projects.single().name)
    }

    // ── Sections ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addSection appends a band to the end of a project's sections`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedSections(Section(id = "s1", projectId = "work", name = "Existing", sortOrder = 0))
        val viewModel = viewModel()

        viewModel.addSection("work", "  New band  ")
        runCurrent()

        val added = viewModel.state.value.sections.first { it.name == "New band" }
        assertEquals(1, added.sortOrder)
    }

    @Test
    fun `addSection with a blank name shows a validation snackbar and creates nothing`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        val viewModel = viewModel()

        viewModel.addSection("work", "   ")
        runCurrent()

        assertTrue(viewModel.state.value.sections.isEmpty())
        val message = viewModel.state.value.snackbarMessage as SnackbarMessage.Text
        assertNull(message.undoAction)
    }

    @Test
    fun `renameSection updates the section's name`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedSections(Section(id = "s1", projectId = "work", name = "Old name"))
        val viewModel = viewModel()

        viewModel.renameSection(Section(id = "s1", projectId = "work", name = "Old name"), "  New name  ")
        runCurrent()

        assertEquals("New name", viewModel.state.value.sections.single().name)
    }

    @Test
    fun `deleteSection removes the heading and leaves its tasks in the project, ungrouped`() = runTest {
        stores.seedProjects(Project(id = "work", name = "Work"))
        stores.seedSections(Section(id = "s1", projectId = "work", name = "Band"))
        stores.seedTasks(task("t1", projectId = "work").copy(sectionId = "s1"))
        val viewModel = viewModel()

        viewModel.deleteSection(Section(id = "s1", projectId = "work", name = "Band"))
        runCurrent()

        assertTrue(viewModel.state.value.sections.isEmpty())
        // Still in the project, one band higher: `Section.sq`'s tombstone frees the tasks it
        // grouped in the same transaction. (An in-memory fake used to leave `s1` on the row here,
        // and this test pinned the fake rather than the store.)
        assertEquals("work", stored("t1").projectId)
        assertNull(stored("t1").sectionId)
    }

    // ── Backup ───────────────────────────────────────────────────────────────────────

    @Test
    fun `exportBackup records the gateway's outcome`() = runTest {
        val exportedAt = Instant.parse("2026-08-11T09:00:00Z")
        backupGateway.exported = BackupOutcome.Exported(projects = 2, tasks = 5, exportedAt = exportedAt)
        val viewModel = viewModel()

        viewModel.exportBackup(BackupTarget("file:///backup.json"))
        runCurrent()

        assertEquals(backupGateway.exported, viewModel.state.value.backupOutcome)
    }

    @Test
    fun `importBackup merges the outcomes of several files into one`() = runTest {
        backupGateway.imports = listOf(
            BackupOutcome.Imported(projects = 1, tasks = 2, files = 1),
            BackupOutcome.Imported(projects = 3, tasks = 4, files = 1),
        )
        val viewModel = viewModel()

        viewModel.importBackup(listOf(BackupTarget("a"), BackupTarget("b")))
        runCurrent()

        val outcome = viewModel.state.value.backupOutcome as BackupOutcome.Imported
        assertEquals(4, outcome.projects)
        assertEquals(6, outcome.tasks)
        assertEquals(2, outcome.files)
        assertEquals(0, outcome.unreadableFiles)
    }

    @Test
    fun `importBackup with only unreadable files reports the first failure`() = runTest {
        backupGateway.imports = listOf(BackupOutcome.Failed(BackupFailure.NOT_JSON))
        val viewModel = viewModel()

        viewModel.importBackup(listOf(BackupTarget("bad")))
        runCurrent()

        assertEquals(BackupOutcome.Failed(BackupFailure.NOT_JSON), viewModel.state.value.backupOutcome)
    }

    @Test
    fun `clearBackupOutcome clears the last outcome`() = runTest {
        backupGateway.exported = BackupOutcome.Exported(projects = 0, tasks = 0, exportedAt = Instant.EPOCH)
        val viewModel = viewModel()
        viewModel.exportBackup(BackupTarget("file:///backup.json"))
        runCurrent()

        viewModel.clearBackupOutcome()
        runCurrent()

        assertNull(viewModel.state.value.backupOutcome)
    }

    // ── Duplicating ──────────────────────────────────────────────────────────────────

    @Test
    fun `duplicating a task copies its checklist, unticked, as new work`() = runTest {
        stores.seedTasks(
            task("t1", title = "Ship it").copy(
                priority = Priority.P1,
                completedAt = Instant.EPOCH,
                spawnedFromId = "older-occurrence",
            ),
            task("s1", title = "Write the notes").copy(
                parentId = "t1",
                completedAt = Instant.EPOCH,
            ),
        )
        val viewModel = viewModel()

        viewModel.duplicateTask(stored("t1"), "Ship it (copy)")
        runCurrent()

        val copy = taskStore.getAll().single { it.title == "Ship it (copy)" }
        assertEquals(Priority.P1, copy.priority)
        // New work: not finished, and not part of anyone's recurrence chain — a copy that claimed
        // to replace an occurrence would take the original's place in every undated list.
        assertNull(copy.completedAt)
        assertNull(copy.spawnedFromId)
        val copiedStep = taskStore.getAll().single { it.parentId == copy.id }
        assertEquals("Write the notes", copiedStep.title)
        assertNull(copiedStep.completedAt)
    }

    // ── Drops ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a drop that moves a task into another project's band does both writes`() = runTest {
        stores.seedTasks(task("t1", projectId = "home"))
        stores.seedProjects(Project(id = "home", name = "Home"), Project(id = "work", name = "Work"))
        stores.seedSections(Section(id = "band", projectId = "work", name = "Band"))
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.MoveTask("t1", "work", "band"))
        runCurrent()

        // The section is written against the row `moveToProject` left behind, not against the
        // snapshot the drag started from — which still named `home` and would have been refused.
        assertEquals("work", stored("t1").projectId)
        assertEquals("band", stored("t1").sectionId)
    }

    @Test
    fun `a drop that reorders writes the order it was handed`() = runTest {
        stores.seedTasks(
            task("a").copy(sortOrder = 0),
            task("b").copy(sortOrder = 1),
            task("c").copy(sortOrder = 2),
        )
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.ReorderTasks(listOf("c", "a", "b")))
        runCurrent()

        assertEquals(0, stored("c").sortOrder)
        assertEquals(1, stored("a").sortOrder)
        assertEquals(2, stored("b").sortOrder)
    }

    @Test
    fun `a rejected drop writes nothing`() = runTest {
        stores.seedTasks(task("t1", projectId = "home"))
        val viewModel = viewModel()
        val before = stored("t1")

        viewModel.applyDropIntent(DropIntent.Rejected)
        runCurrent()

        assertEquals(before, stored("t1"))
    }

    @Test
    fun `a drop that nests a project keeps its name and colour`() = runTest {
        stores.seedProjects(
            Project(id = "home", name = "Home", colorHex = "#123456"),
            Project(id = "work", name = "Work"),
        )
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.NestProject("home", "work"))
        runCurrent()

        val nested = viewModel.state.value.project("home")!!
        assertEquals("work", nested.parentId)
        assertEquals("Home", nested.name)
        assertEquals("#123456", nested.colorHex)
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `adding a tag stores it and a duplicate name raises a snackbar instead`() = runTest {
        val viewModel = viewModel()

        viewModel.addTag("Errand", "#BA1A1A")
        runCurrent()
        assertEquals(listOf("Errand"), tagStore.getAll().map { it.name })

        viewModel.addTag("errand", "#006A60")
        runCurrent()
        assertEquals(1, tagStore.getAll().size)
        assertTrue(viewModel.state.value.snackbarMessage != null)
    }

    @Test
    fun `toggling a tag on a task adds it and toggling again removes it`() = runTest {
        stores.seedTasks(task("t1"))
        val tag = stores.putTag(Tag(name = "Errand"))
        val viewModel = viewModel()

        viewModel.toggleTag(stored("t1"), tag.id)
        runCurrent()
        assertEquals(listOf(tag.id), stored("t1").tagIds)

        viewModel.toggleTag(stored("t1"), tag.id)
        runCurrent()
        assertEquals(emptyList<String>(), stored("t1").tagIds)
    }

    /** Written straight through, not deferred behind the undo window: no work disappears, so
     *  there is nothing for an undo to give back. */
    @Test
    fun `deleting a tag takes effect at once and leaves the task's other labels`() = runTest {
        val errand = stores.putTag(Tag(id = "g1", name = "Errand"))
        stores.putTag(Tag(id = "g2", name = "Waiting"))
        stores.seedTasks(task("t1").copy(tagIds = listOf("g1", "g2")))
        val viewModel = viewModel()

        viewModel.deleteTag(errand)
        runCurrent()

        assertEquals(listOf("g2"), tagStore.getAll().map { it.id })
        // The id stays on the row; the read side is what drops it.
        assertEquals(listOf("g1", "g2"), stored("t1").tagIds)
        assertEquals(listOf("g2"), viewModel.state.value.tagsOf(stored("t1")).map { it.id })
    }

    /**
     * What dropping a task on a tag row does, end to end.
     *
     * Add-only, deliberately: the dragged row is a snapshot, and a toggle resolving against a task
     * that gained the label meanwhile would take it back off. `resolveDrop` already refuses the
     * drop when the snapshot wears it, so this path can only ever add.
     */
    @Test
    fun `a task dropped on a tag keeps the labels it already had`() = runTest {
        stores.putTag(Tag(id = "g1", name = "Errand"))
        stores.putTag(Tag(id = "g2", name = "Waiting"))
        stores.seedTasks(task("t1").copy(tagIds = listOf("g2")))
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.TagTask("t1", "g1"))
        runCurrent()

        assertEquals(listOf("g2", "g1"), stored("t1").tagIds)
    }

    @Test
    fun `dragging a tag into a gap writes the new order`() = runTest {
        stores.putTag(Tag(id = "g1", name = "Errand", sortOrder = 0))
        stores.putTag(Tag(id = "g2", name = "Waiting", sortOrder = 1))
        val viewModel = viewModel()

        viewModel.applyDropIntent(DropIntent.ReorderTags(listOf("g2", "g1")))
        runCurrent()

        assertEquals(listOf("g2", "g1"), tagStore.getAll().map { it.id })
    }

    /**
     * Quick-add's one asymmetry with `#project`: a handle that matched nothing creates the tag
     * before the task, so the line "Post the parcel @waiting" leaves both behind.
     */
    @Test
    fun `a quick-add handle that matches nothing creates the tag and applies it`() = runTest {
        val known = stores.putTag(Tag(id = "g1", name = "Errand"))
        val viewModel = viewModel()

        viewModel.addParsedTask(
            ParsedQuickAdd(
                title = "Post the parcel",
                tagIds = listOf(known.id),
                newTagNames = listOf("waiting"),
            ),
        )
        runCurrent()

        assertEquals(listOf("Errand", "waiting"), tagStore.getAll().map { it.name })
        val created = tagStore.getAll().first { it.name == "waiting" }
        assertEquals(listOf("g1", created.id), taskStore.getAll().single().tagIds)
    }

    /** A name that cannot be saved is left off the task rather than failing the capture — the
     *  point of quick-add is that the task lands. */
    @Test
    fun `a quick-add handle that clashes with an existing tag still files the task`() = runTest {
        stores.putTag(Tag(id = "g1", name = "Errand"))
        val viewModel = viewModel()

        viewModel.addParsedTask(
            ParsedQuickAdd(title = "Post the parcel", newTagNames = listOf("errand")),
        )
        runCurrent()

        assertEquals(1, tagStore.getAll().size)
        assertEquals("Post the parcel", taskStore.getAll().single().title)
        assertEquals(emptyList<String>(), taskStore.getAll().single().tagIds)
    }

    // ── Attachments ──────────────────────────────────────────────────────────────────

    @Test
    fun `a link is filed on the task and shows up in the state`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.addLinkAttachment(task("t1"), "https://example.org/receipt", "Receipt")
        runCurrent()

        val stored = stores.attachments().single()
        assertEquals(AttachmentKind.LINK, stored.kind)
        assertEquals("https://example.org/receipt", stored.url)
        assertEquals("Receipt", stored.name)
        assertEquals(listOf(stored.id), viewModel.state.value.attachmentsOf("t1").map { it.id })
        assertEquals(1, viewModel.state.value.attachmentCount("t1"))
    }

    /** The repository refuses it; the ViewModel is what says so out loud. */
    @Test
    fun `a blank link is refused with a message rather than filed`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.addLinkAttachment(task("t1"), "   ", "Receipt")
        runCurrent()

        assertTrue(stores.attachments().isEmpty())
        assertNotNull(viewModel.state.value.snackbarMessage)
    }

    @Test
    fun `a picked file is copied in, and its bytes are read exactly once`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        var opened = 0

        viewModel.addFileAttachment(
            task("t1"),
            PickedFile(name = "invoice.pdf", mimeType = "application/pdf") {
                opened++
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
        )
        runCurrent()

        assertEquals(1, opened)
        val stored = stores.attachments().single()
        assertEquals("invoice.pdf", stored.name)
        assertEquals(3L, stored.sizeBytes)
        assertNotNull(stored.sha256)
        // The bytes are on disk, so the row draws as openable rather than as missing.
        assertTrue(viewModel.state.value.isPresent(stored))
    }

    /**
     * A picker can hand back a uri whose provider is already gone — a cloud file the user just
     * signed out of, a card pulled between the tap and the read. That is a message, not a crash.
     */
    @Test
    fun `a file whose bytes cannot be opened raises a message instead of throwing`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()

        viewModel.addFileAttachment(
            task("t1"),
            PickedFile(name = "gone.pdf", mimeType = "application/pdf") {
                throw java.io.FileNotFoundException("no bytes")
            },
        )
        runCurrent()

        assertTrue(stores.attachments().isEmpty())
        assertNotNull(viewModel.state.value.snackbarMessage)
    }

    @Test
    fun `removing an attachment takes the row with it`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        viewModel.addLinkAttachment(task("t1"), "https://example.org", "Receipt")
        runCurrent()

        viewModel.deleteAttachment(stores.attachments().single())
        runCurrent()

        assertTrue(stores.attachments().isEmpty())
        assertEquals(0, viewModel.state.value.attachmentCount("t1"))
    }

    @Test
    fun `opening a link hands it to the platform`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        viewModel.addLinkAttachment(task("t1"), "https://example.org", "Receipt")
        runCurrent()

        viewModel.openAttachment(stores.attachments().single())

        assertEquals(listOf("https://example.org"), attachmentOpener.openedLinks)
        assertNull(viewModel.state.value.snackbarMessage)
    }

    /** A phone with no viewer for the type is an ordinary state, and the user has to be told —
     *  a tap that does nothing at all reads as a broken row. */
    @Test
    fun `a machine that opens nothing says so`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        viewModel.addLinkAttachment(task("t1"), "https://example.org", "Receipt")
        runCurrent()
        attachmentOpener.canOpen = false

        viewModel.openAttachment(stores.attachments().single())
        runCurrent()

        assertNotNull(viewModel.state.value.snackbarMessage)
    }

    /** The rows a delete is about to take are hidden at once — the paperclip must not outlive
     *  the row it hangs off for the length of the undo window. */
    @Test
    fun `a pending task delete hides its attachments too`() = runTest {
        stores.seedTasks(task("t1"))
        val viewModel = viewModel()
        viewModel.addLinkAttachment(task("t1"), "https://example.org", "Receipt")
        runCurrent()
        assertEquals(1, viewModel.state.value.attachmentCount("t1"))

        viewModel.deleteTask(task("t1"))
        runCurrent()

        assertEquals(0, viewModel.state.value.attachmentCount("t1"))
        // Still in the database: nothing has been committed yet, and an undo brings both back.
        assertEquals(1, stores.attachments().size)
    }
}
