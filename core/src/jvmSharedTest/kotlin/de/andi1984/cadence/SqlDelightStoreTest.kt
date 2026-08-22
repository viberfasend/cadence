package de.andi1984.cadence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `.sq` queries against a real (in-memory) SQLite database — the JVM tests carry this, per
 * `docs/agents/`'s testing conventions, since it needs no Android runtime.
 */
class SqlDelightStoreTest {

    private fun newDatabase(): CadenceDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    private val now = Instant.parse("2026-08-08T09:00:00Z")

    @Test
    fun `a task survives an insert and read back`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val task = Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now)

        taskStore.insert(task)

        assertEquals(task, taskStore.byId("t1"))
        assertEquals(listOf(task), taskStore.getAll())
    }

    @Test
    fun `completeIfOpen reports 1 once and 0 on a second call`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Call the vet", createdAt = now, updatedAt = now))

        val first = taskStore.completeIfOpen("t1", now)
        val second = taskStore.completeIfOpen("t1", now)

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(now, taskStore.byId("t1")?.completedAt)
    }

    @Test
    fun `reopenIfDone mirrors completeIfOpen`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Call the vet", createdAt = now, updatedAt = now))
        taskStore.completeIfOpen("t1", now)

        val first = taskStore.reopenIfDone("t1", now)
        val second = taskStore.reopenIfDone("t1", now)

        assertEquals(1, first)
        assertEquals(0, second)
        assertNull(taskStore.byId("t1")?.completedAt)
    }

    @Test
    fun `deleting a project moves its tasks to the Inbox unless deleteTasks is set`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        projectStore.tombstoneWithChildren("p1", deleteTasks = false, at = now)

        assertNull(taskStore.byId("t1")?.projectId)
        assertTrue(projectStore.getAll().isEmpty())
    }

    @Test
    fun `deleting a project with deleteTasks removes its tasks too`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        projectStore.tombstoneWithChildren("p1", deleteTasks = true, at = now)

        assertNull(taskStore.byId("t1"))
    }

    /** The danger zone in SQL: the rows stay, stamped, so the wipe travels to the other devices
     *  the way every other delete does. */
    @Test
    fun `tombstoneAll empties both tables without removing a row`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        taskStore.tombstoneAll(now)
        projectStore.tombstoneAll(now)

        assertTrue(taskStore.getAll().isEmpty())
        assertTrue(projectStore.getAll().isEmpty())
        assertEquals(now.toEpochMilli(), database.taskQueries.selectAllIncludingDeleted().executeAsOne().deletedAt)
        assertEquals(now.toEpochMilli(), database.projectQueries.selectAllIncludingDeleted().executeAsOne().deletedAt)
    }

    /** Idempotent, like every other tombstone here: a second wipe must not restamp what the first
     *  one already deleted, or it would win a merge it has no business winning. */
    @Test
    fun `tombstoneAll leaves an existing tombstone's timestamp alone`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now))

        taskStore.tombstoneAll(now)
        taskStore.tombstoneAll(now.plusSeconds(60))

        assertEquals(
            now.toEpochMilli(),
            database.taskQueries.selectAllIncludingDeleted().executeAsOne().deletedAt,
        )
    }

    @Test
    fun `restoring a backup merges into what is already stored`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "stale", title = "Old task", createdAt = now, updatedAt = now))

        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "Home", updatedAt = now)),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Fresh task", projectId = "p1", createdAt = now, updatedAt = now)),
            revivedAt = now,
        )

        // The row the file knows nothing about survives. This assertion was the exact opposite
        // before ADR 0002: importing replaced both tables, so anything added since the file was
        // written was silently discarded — the reason two devices sharing one file each undid
        // the other.
        assertEquals("Old task", taskStore.byId("stale")?.title)
        assertEquals("Fresh task", taskStore.byId("t1")?.title)
        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
    }

    @Test
    fun `merging keeps whichever version was written last`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val later = now.plusSeconds(60)
        taskStore.insert(Task(id = "t1", title = "Local wins", createdAt = now, updatedAt = later))

        // Older than what is stored, so it loses; a tie would also keep the stored row.
        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Stale", createdAt = now, updatedAt = now)),
            revivedAt = now,
        )
        assertEquals("Local wins", taskStore.byId("t1")?.title)

        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Newer", createdAt = now, updatedAt = later.plusSeconds(1))),
            revivedAt = now,
        )
        assertEquals("Newer", taskStore.byId("t1")?.title)
    }

    @Test
    fun `a tombstone competes on its timestamp like any other version`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val later = now.plusSeconds(60)
        taskStore.insert(Task(id = "t1", title = "Edited here", createdAt = now, updatedAt = later))

        // A delete older than the local edit must lose. The first cut of mergeAll special-cased
        // deletedAt into an unconditional delete, so a stale delete beat a newer edit.
        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Deleted there", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = now,
        )
        assertEquals("Edited here", taskStore.byId("t1")?.title)

        // A newer one wins, and the row goes invisible without being removed.
        val deletedAt = later.plusSeconds(1)
        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(
                Task(id = "t1", title = "Deleted there", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt),
            ),
            revivedAt = now,
        )
        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `a tombstone for a task we have never seen is stored, not dropped`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)

        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Gone", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = now,
        )
        assertNull(taskStore.byId("t1"))
    }

    /**
     * A file is an instruction, not a version — the one place the merge rule bends.
     *
     * Everywhere else, an incoming record older than a local tombstone loses, which is what stops
     * a sync round from resurrecting what another device deleted. Importing is a person pointing
     * at a file and asking for its contents, and answering that by writing nothing at all — while
     * still reporting the file's counts — is how a Todoist re-import came back empty: the tool
     * derives its ids from project names, so the second import carried exactly the ids the delete
     * had just tombstoned, each stamped when the *file* was written.
     *
     * The revived row is stamped [revivedAt] rather than keeping the file's timestamp, or the
     * server's tombstone would win the next round and delete it straight back.
     */
    @Test
    fun `importing a file restores what this device had deleted`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val deletedAt = now.plusSeconds(600)
        projectStore.insert(Project(id = "p1", name = "Import", updatedAt = deletedAt, deletedAt = deletedAt))
        taskStore.insert(
            Task(id = "t1", title = "Gone", projectId = "p1", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt),
        )
        val importedAt = deletedAt.plusSeconds(60)

        // The file is older than the tombstones — it was written before the delete.
        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "Import", updatedAt = now)),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Back", projectId = "p1", createdAt = now, updatedAt = now)),
            revivedAt = importedAt,
        )

        assertEquals("Back", taskStore.byId("t1")?.title)
        assertEquals(importedAt, taskStore.byId("t1")?.updatedAt)
        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
        assertEquals(importedAt, projectStore.getAll().single().updatedAt)
    }

    /** The other half of that rule: a file's own tombstone is still only a version, so it does not
     *  revive anything and it still loses to a newer local edit. */
    @Test
    fun `an imported tombstone revives nothing`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val deletedAt = now.plusSeconds(600)
        taskStore.insert(Task(id = "t1", title = "Gone", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt))

        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Also gone", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = deletedAt.plusSeconds(60),
        )

        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `a tombstoned task is invisible to every read`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "parent", title = "Parent", createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "step", title = "Step", parentId = "parent", createdAt = now, updatedAt = now))
        taskStore.insert(
            Task(id = "next", title = "Next", spawnedFromId = "parent", createdAt = now, updatedAt = now),
        )

        taskStore.tombstoneWithSubtasks("parent", now)

        assertNull(taskStore.byId("parent"))
        assertTrue(taskStore.getAll().none { it.id == "parent" || it.id == "step" })
        // The step went with its task — a step without its task has no meaning.
        assertTrue(taskStore.subtasksOf("parent").isEmpty())
        // Completing one is not merely a no-op, it must *report* that it did nothing, or the
        // caller schedules a successor for a task nobody can see.
        assertEquals(0, taskStore.completeIfOpen("parent", now))
        // The successor is a separate row and is untouched.
        assertEquals(listOf("next"), taskStore.openSuccessorsOf("parent"))
    }

    /**
     * The attachment queries take id lists straight from a delete, and the danger zone hands
     * over *every* task id. `IN :taskIds` becomes one bind parameter per element, and
     * `SQLITE_MAX_VARIABLE_NUMBER` is 999 on the SQLite that Android API 26-29 ship — so an
     * unchunked list is a crash on a real phone with a thousand tasks on it.
     *
     * This driver cannot reproduce that: xerial's limit is 32766, which is why the bug reached
     * a release in the first place. What the test pins is the chunking's *behaviour* over a list
     * that spans several chunks — every row deleted, not just the first chunk's, and hashes
     * deduplicated across chunk boundaries rather than only within one, which is all `DISTINCT`
     * can do once the query runs more than once.
     */
    @Test
    fun `the attachment id lists survive being longer than one chunk`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.Unconfined)

        // Comfortably past 999, and past whatever chunk size the store picks below it.
        val taskIds = (1..1500).map { "t$it" }
        taskIds.forEachIndexed { index, id ->
            taskStore.insert(Task(id = id, title = "Task $index", createdAt = now, updatedAt = now))
            attachmentStore.insert(
                Attachment(
                    id = "a$index",
                    taskId = id,
                    kind = AttachmentKind.FILE,
                    name = "photo.png",
                    mimeType = "image/png",
                    // Three blobs shared by 500 rows each: the same hash lands in every chunk,
                    // so a per-chunk DISTINCT alone would report it more than once.
                    sha256 = "sha${index % 3}",
                    createdAt = now,
                ),
            )
        }

        val hashes = attachmentStore.hashesForTasks(taskIds)
        assertEquals(listOf("sha0", "sha1", "sha2"), hashes.sorted())

        // Every one of them is still named by a row, and asking about 1500 hashes at once is
        // itself a list that has to be chunked.
        val probed = List(1500) { "sha${it % 3}" }
        assertEquals(listOf("sha0", "sha1", "sha2"), attachmentStore.stillReferenced(probed).sorted())

        attachmentStore.deleteForTasks(taskIds)

        assertTrue(taskIds.all { attachmentStore.forTask(it).isEmpty() })
        assertTrue(attachmentStore.referencedHashes().isEmpty())
    }

    // ── Manual order ───────────────────────────────────────────────────────────────
    //
    // Two things here are SQL, not Kotlin, and only a real database can say whether they hold:
    // the `sortOrder != :sortOrder` guard, and `projectId IS :projectId` treating the Inbox as a
    // bucket (`= NULL` matches nothing in SQLite, so `=` would report every list as empty).

    @Test
    fun `reorder writes the rows that moved and leaves the rest alone`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "a", title = "A", sortOrder = 0, createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "b", title = "B", sortOrder = 1, createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "c", title = "C", sortOrder = 2, createdAt = now, updatedAt = now))

        val later = now.plusSeconds(60)
        taskStore.reorder(listOf("a" to 0, "c" to 1, "b" to 2), later)

        assertEquals(0, taskStore.byId("a")?.sortOrder)
        assertEquals(2, taskStore.byId("b")?.sortOrder)
        assertEquals(1, taskStore.byId("c")?.sortOrder)
        // `a` never moved, so the push must not see it.
        assertEquals(now, taskStore.byId("a")?.updatedAt)
        assertEquals(later, taskStore.byId("b")?.updatedAt)
    }

    @Test
    fun `maxSortOrder reads the Inbox as a bucket of its own`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(
            Task(id = "p", title = "Filed", projectId = "p1", sortOrder = 7, createdAt = now, updatedAt = now),
        )
        taskStore.insert(Task(id = "i", title = "Inbox", sortOrder = 2, createdAt = now, updatedAt = now))
        // A step is numbered inside its parent's checklist and must not raise the project's max.
        taskStore.insert(
            Task(id = "s", title = "Step", projectId = "p1", parentId = "p", sortOrder = 40, createdAt = now, updatedAt = now),
        )

        assertEquals(7, taskStore.maxSortOrder("p1"))
        assertEquals(2, taskStore.maxSortOrder(null))
        assertNull(taskStore.maxSortOrder("unknown-project"))
    }

    @Test
    fun `a tombstoned row is neither reordered nor counted as the last position`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "live", title = "Live", sortOrder = 0, createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "gone", title = "Gone", sortOrder = 9, createdAt = now, updatedAt = now))
        taskStore.tombstoneWithSubtasks("gone", now)

        taskStore.reorder(listOf("gone" to 0, "live" to 1), now.plusSeconds(60))

        // The live row took the position it was given; the tombstone answered to neither
        // statement, so it is not the list's last position either.
        assertEquals(1, taskStore.byId("live")?.sortOrder)
        assertEquals(1, taskStore.maxSortOrder(null))
    }

    @Test
    fun `projects and sections number their own buckets`() = runTest {
        val database = newDatabase()
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val sectionStore = SqlDelightSectionStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "root", name = "Root", sortOrder = 3, updatedAt = now))
        projectStore.insert(Project(id = "child", name = "Child", parentId = "root", sortOrder = 1, updatedAt = now))
        sectionStore.insert(Section(id = "s1", projectId = "root", name = "Band", sortOrder = 5, updatedAt = now))

        assertEquals(3, projectStore.maxSortOrder(null))
        assertEquals(1, projectStore.maxSortOrder("root"))
        assertNull(projectStore.maxSortOrder("child"))
        assertEquals(5, sectionStore.maxSortOrder("root"))
        assertNull(sectionStore.maxSortOrder("child"))
    }

    // ── Tags ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a tag survives an insert and read back`() = runTest {
        val database = newDatabase()
        val tagStore = SqlDelightTagStore(database, Dispatchers.Unconfined)
        val tag = Tag(id = "g1", name = "Errand", colorHex = "#BA1A1A", updatedAt = now)

        tagStore.insert(tag)

        assertEquals(listOf(tag), tagStore.getAll())
    }

    /**
     * The packed column, through the real mapper.
     *
     * Worth a database test rather than only a codec one: `4.sqm` appends `tagIds` after
     * `sectionId`, every query is a `SELECT *`, and a mapper whose parameters are one position out
     * reads a task's tags out of its section id without failing anywhere.
     */
    @Test
    fun `a task's tags survive the packed column`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val task = Task(
            id = "t1",
            title = "Post the parcel",
            sectionId = "s1",
            tagIds = listOf("g2", "g1"),
            createdAt = now,
            updatedAt = now,
        )

        taskStore.insert(task)

        val read = taskStore.byId("t1")!!
        assertEquals(listOf("g2", "g1"), read.tagIds)
        assertEquals("s1", read.sectionId)
    }

    @Test
    fun `a task with no tags reads back as no tags, not as one blank one`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Post the parcel", createdAt = now, updatedAt = now))

        assertEquals(emptyList<String>(), taskStore.byId("t1")?.tagIds)
    }

    /** Deleting a tag is one row, and the id stays on every task that wore it — see `Tag.sq`. */
    @Test
    fun `tombstoning a tag leaves the tasks that named it untouched`() = runTest {
        val database = newDatabase()
        val tagStore = SqlDelightTagStore(database, Dispatchers.Unconfined)
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        tagStore.insert(Tag(id = "g1", name = "Errand", updatedAt = now))
        taskStore.insert(
            Task(id = "t1", title = "Post", tagIds = listOf("g1"), createdAt = now, updatedAt = now),
        )

        tagStore.tombstone("g1", now.plusSeconds(1))

        assertTrue(tagStore.getAll().isEmpty())
        assertEquals(listOf("g1"), taskStore.byId("t1")?.tagIds)
        assertEquals(now, taskStore.byId("t1")?.updatedAt)
    }

    @Test
    fun `tombstoning a tag twice keeps the first timestamp`() = runTest {
        val database = newDatabase()
        val tagStore = SqlDelightTagStore(database, Dispatchers.Unconfined)
        tagStore.insert(Tag(id = "g1", name = "Errand", updatedAt = now))

        tagStore.tombstone("g1", now.plusSeconds(1))
        tagStore.tombstone("g1", now.plusSeconds(60))

        val row = database.tagQueries.selectByIdIncludingDeleted("g1").executeAsOne()
        assertEquals(now.plusSeconds(1).toEpochMilli(), row.deletedAt)
    }
}
