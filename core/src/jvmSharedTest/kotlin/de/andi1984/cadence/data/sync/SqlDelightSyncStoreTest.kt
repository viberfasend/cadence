package de.andi1984.cadence.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What sync remembers, and the two tombstone-aware views only it uses. */
class SqlDelightSyncStoreTest {

    private fun newDatabase(): CadenceDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    private fun syncStore(database: CadenceDatabase) =
        SqlDelightSyncStore(database, Dispatchers.Unconfined)

    private val now = Instant.parse("2026-08-11T09:00:00Z")

    @Test
    fun `a fresh database starts signed out, with no cursor and a watermark at the epoch`() =
        runTest {
            val state = syncStore(newDatabase()).state()

            assertNull(state.session)
            assertNull(state.taskCursor)
            assertNull(state.projectCursor)
            assertEquals(Instant.EPOCH, state.pushWatermark)
            assertNull(state.lastSyncedAt)
        }

    @Test
    fun `the session round-trips and signing out clears it along with the cursors`() = runTest {
        val store = syncStore(newDatabase())

        store.setSession("{\"access_token\":\"abc\"}")
        store.mergeAndAdvance(emptyList(), emptyList(), "2026-08-11T09:00:00Z", null)
        store.setPushWatermark(now)
        store.setLastSyncedAt(now)

        assertEquals("{\"access_token\":\"abc\"}", store.state().session)
        assertEquals("2026-08-11T09:00:00Z", store.state().taskCursor)

        store.clear()

        val cleared = store.state()
        assertNull(cleared.session)
        assertNull(cleared.taskCursor)
        assertNull(cleared.lastSyncedAt)
        assertEquals(Instant.EPOCH, cleared.pushWatermark)
    }

    /** A fresh device has pushed nothing, so its first push is everything it has — tombstones
     *  included, because a delete made before signing in still has to reach the other device. */
    @Test
    fun `a watermark at the epoch selects every row, tombstones included`() = runTest {
        val database = newDatabase()
        val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        tasks.insert(Task(id = "t1", title = "Alive", createdAt = now, updatedAt = now))
        tasks.insert(Task(id = "t2", title = "Deleted", createdAt = now, updatedAt = now))
        tasks.tombstoneWithSubtasks("t2", now)

        val pushed = syncStore(database).tasksChangedSince(Instant.EPOCH)

        assertEquals(setOf("t1", "t2"), pushed.map { it.id }.toSet())
        assertEquals(now, pushed.first { it.id == "t2" }.deletedAt)
    }

    @Test
    fun `only rows written after the watermark are pushed again`() = runTest {
        val database = newDatabase()
        val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val later = now.plusSeconds(60)
        tasks.insert(Task(id = "t1", title = "Old", createdAt = now, updatedAt = now))
        tasks.insert(Task(id = "t2", title = "New", createdAt = now, updatedAt = later))

        val pushed = syncStore(database).tasksChangedSince(now)

        assertEquals(listOf("t2"), pushed.map { it.id })
    }

    @Test
    fun `merging a page applies last-writer-wins and advances the cursor together`() = runTest {
        val database = newDatabase()
        val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projects = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val store = syncStore(database)
        tasks.insert(Task(id = "t1", title = "Local", createdAt = now, updatedAt = now))
        projects.insert(Project(id = "p1", name = "Local", updatedAt = now))

        store.mergeAndAdvance(
            projects = listOf(Project(id = "p1", name = "Stale", updatedAt = now.minusSeconds(1))),
            tasks = listOf(Task(id = "t1", title = "Newer", createdAt = now, updatedAt = now.plusSeconds(1))),
            taskCursor = "2026-08-11T09:00:01Z",
            projectCursor = "2026-08-11T09:00:01Z",
        )

        assertEquals("Newer", tasks.byId("t1")?.title)
        assertEquals("Local", projects.getAll().single().name)
        assertEquals("2026-08-11T09:00:01Z", store.state().taskCursor)
    }

    /**
     * The half of the merge rule that importing a file deliberately bends, and sync must not.
     *
     * A pulled row older than a local tombstone loses, full stop. Reviving it here would undo
     * every delete the moment the other device pushed its copy back — the resurrection loop
     * tombstones exist to prevent. Importing is a person asking for a file's contents; a pull is
     * two devices agreeing on a version.
     */
    @Test
    fun `a pulled row older than a local tombstone stays deleted`() = runTest {
        val database = newDatabase()
        val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val store = syncStore(database)
        val deletedAt = now.plusSeconds(600)
        tasks.insert(Task(id = "t1", title = "Deleted here", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt))

        store.mergeAndAdvance(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Still there", createdAt = now, updatedAt = now)),
            taskCursor = "2026-08-11T09:00:01Z",
            projectCursor = null,
        )

        assertNull(tasks.byId("t1"))
    }

    /** A page can be empty for one table and full for the other, and a null cursor there must
     *  leave the stored one alone rather than rewind it. */
    @Test
    fun `a null cursor leaves that table's cursor where it was`() = runTest {
        val store = syncStore(newDatabase())
        store.mergeAndAdvance(emptyList(), emptyList(), "2026-08-11T09:00:00Z", "2026-08-11T08:00:00Z")

        store.mergeAndAdvance(emptyList(), emptyList(), "2026-08-11T10:00:00Z", null)

        assertEquals("2026-08-11T10:00:00Z", store.state().taskCursor)
        assertEquals("2026-08-11T08:00:00Z", store.state().projectCursor)
    }

    @Test
    fun `the sweep drops tombstones past the horizon and keeps everything else`() = runTest {
        val database = newDatabase()
        val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val store = syncStore(database)
        val old = now.minusSeconds(200L * 24 * 60 * 60)
        tasks.insert(Task(id = "t1", title = "Long gone", createdAt = old, updatedAt = old))
        tasks.insert(Task(id = "t2", title = "Just gone", createdAt = now, updatedAt = now))
        tasks.insert(Task(id = "t3", title = "Alive", createdAt = now, updatedAt = now))
        tasks.tombstoneWithSubtasks("t1", old)
        tasks.tombstoneWithSubtasks("t2", now)

        store.collectTombstones(before = now.minusSeconds(90L * 24 * 60 * 60), at = now)

        val remaining = store.tasksChangedSince(Instant.EPOCH).map { it.id }.toSet()
        assertEquals(setOf("t2", "t3"), remaining)
        assertTrue(store.state().lastSweepAt != null)
    }
}
