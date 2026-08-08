package de.andi1984.cadence.data

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Content-addressed storage for attachment bytes, keyed by their own SHA-256.
 *
 * Takes a [File] root rather than a `Context`, so `AppContainer` passes
 * `File(context.filesDir, "attachments")` and the JVM tests pass a JUnit `TemporaryFolder` — no
 * Robolectric, and the same class is what the desktop app will use directly, since it carries no
 * Android dependency at all.
 *
 * Layout is `<root>/<first two hex chars>/<full sha256>`, extensionless — the two-character
 * fan-out keeps any one directory small. [root] and [tmp] must sit on the same filesystem (both
 * under the app's private storage on Android), because the copy finishes with a `renameTo` that
 * is only atomic within one volume.
 */
class BlobStore(private val root: File, private val tmp: File) {

    /** Streams [source] into the store, hashing as it goes; aborts past [maxBytes]. */
    fun store(source: InputStream, maxBytes: Long): StoreResult {
        tmp.mkdirs()
        val staged = File(tmp, "blob-${java.util.UUID.randomUUID()}")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var tooLarge = false
            // `break`, not `return` — deleting `staged` while its own output stream is still
            // open fails silently on Windows (no open-file unlink like POSIX has), and a `return`
            // from inside `use {}` only closes the stream *after* this line runs. Breaking out of
            // the loop lets both `use` blocks close normally first, so the delete below actually
            // has an unlocked file to remove.
            DigestInputStream(source, digest).use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > maxBytes) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                staged.delete()
                return StoreResult.TooLarge
            }
            val sha256 = digest.digest().toHex()
            val destination = fileFor(sha256)
            if (destination.exists()) {
                // Same bytes, already on disk — the copy was a duplicate. That is the whole
                // dedupe: drop the temp file and hand back the existing blob's identity.
                staged.delete()
            } else {
                destination.parentFile?.mkdirs()
                if (!staged.renameTo(destination)) {
                    staged.delete()
                    return StoreResult.Failed(IllegalStateException("could not place blob $sha256"))
                }
            }
            StoreResult.Ok(sha256, total)
        } catch (t: Throwable) {
            staged.delete()
            StoreResult.Failed(t)
        }
    }

    /** The blob's file, or null when it is not (or no longer) on disk. */
    fun file(sha256: String): File? = fileFor(sha256).takeIf { it.exists() }

    /** Which of [hashes] are actually present on disk right now. */
    fun present(hashes: Collection<String>): Set<String> = hashes.filterTo(mutableSetOf()) { fileFor(it).exists() }

    fun deleteAll(hashes: Collection<String>) {
        hashes.forEach { fileFor(it).delete() }
    }

    /**
     * Removes anything on disk nobody names, plus leftover temp files.
     *
     * Run once at cold start (heals a leak from a process killed mid-copy) and after a backup
     * restore (a full replace makes per-hash reclaim meaningless). Listing a few hundred files is
     * free, so no incremental bookkeeping is worth the complexity.
     */
    fun sweepOrphans(referenced: Set<String>) {
        root.listFiles()?.forEach { shard ->
            if (!shard.isDirectory) return@forEach
            shard.listFiles()?.forEach { blob ->
                if (blob.name !in referenced) blob.delete()
            }
            if (shard.listFiles()?.isEmpty() == true) shard.delete()
        }
        tmp.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(sha256: String): File = File(File(root, sha256.take(2)), sha256)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

sealed interface StoreResult {
    data class Ok(val sha256: String, val sizeBytes: Long) : StoreResult
    data object TooLarge : StoreResult
    data class Failed(val cause: Throwable) : StoreResult
}
