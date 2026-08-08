package de.andi1984.cadence

import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.StoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Content-addressed storage against a real filesystem — a `TemporaryFolder`, no Robolectric,
 * since [BlobStore] takes a plain [java.io.File] and never touches Android.
 */
class BlobStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): BlobStore = BlobStore(root = folder.newFolder("root"), tmp = folder.newFolder("tmp"))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `storing the same bytes twice writes one file on disk`() {
        val blobStore = store()
        val bytes = "invoice".toByteArray()

        val first = blobStore.store(ByteArrayInputStream(bytes), maxBytes = 1024) as StoreResult.Ok
        val second = blobStore.store(ByteArrayInputStream(bytes), maxBytes = 1024) as StoreResult.Ok

        assertEquals(first.sha256, second.sha256)
        assertEquals(sha256(bytes), first.sha256)
        val onDisk = folder.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, onDisk.size)
    }

    @Test
    fun `a stream past the cap leaves no temp file behind`() {
        val blobStore = store()
        val bytes = ByteArray(2048)

        val result = blobStore.store(ByteArrayInputStream(bytes), maxBytes = 1024)

        assertTrue(result is StoreResult.TooLarge)
        val leftovers = folder.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `a stored blob is found by its hash and a missing one is not`() {
        val blobStore = store()
        val result = blobStore.store(ByteArrayInputStream("photo".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok

        assertNotNull(blobStore.file(result.sha256))
        assertNull(blobStore.file("0".repeat(64)))
    }

    @Test
    fun `present reports only the hashes actually on disk`() {
        val blobStore = store()
        val result = blobStore.store(ByteArrayInputStream("photo".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok

        val present = blobStore.present(listOf(result.sha256, "0".repeat(64)))

        assertEquals(setOf(result.sha256), present)
    }

    @Test
    fun `deleteAll removes the named blobs and nothing else`() {
        val blobStore = store()
        val kept = (blobStore.store(ByteArrayInputStream("kept".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok).sha256
        val removed = (blobStore.store(ByteArrayInputStream("removed".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok).sha256

        blobStore.deleteAll(listOf(removed))

        assertNotNull(blobStore.file(kept))
        assertNull(blobStore.file(removed))
    }

    @Test
    fun `sweepOrphans keeps referenced blobs and removes the rest, plus stray temp files`() {
        val blobStore = store()
        val kept = (blobStore.store(ByteArrayInputStream("kept".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok).sha256
        val orphan = (blobStore.store(ByteArrayInputStream("orphan".toByteArray()), maxBytes = 1024)
            as StoreResult.Ok).sha256
        folder.newFile("tmp/leftover.tmp")

        blobStore.sweepOrphans(referenced = setOf(kept))

        assertNotNull(blobStore.file(kept))
        assertNull(blobStore.file(orphan))
        val tmpContents = folder.root.resolve("tmp").listFiles().orEmpty()
        assertTrue(tmpContents.isEmpty())
    }
}
