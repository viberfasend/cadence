package de.andi1984.cadence.data.db

/**
 * The `tagIds` column: a task's tag ids packed into one TEXT field, comma-separated.
 *
 * A private storage detail, exactly like [RecurrenceCodec] beside it — the *published* shapes are
 * `BackupTask.tagIds` and `RemoteTask.tag_ids`, both of which are real JSON arrays. Nothing
 * outside this file should look at the raw string.
 *
 * No version prefix and no escaping, unlike [RecurrenceCodec], because there is nothing here to
 * version or escape: every element is a UUIDv7 rendered as hex and dashes, so a comma can never
 * occur inside one and the field can only ever grow another id. Anything that is not shaped like
 * an id is dropped on the way in rather than throwing — a malformed column costs a task its
 * labels, never the whole list.
 */
object TagIdsCodec {

    private const val SEPARATOR = ","

    /** Null for a task with no tags, so the column stays empty rather than holding `""`. */
    fun encode(tagIds: List<String>): String? =
        tagIds.filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(SEPARATOR)

    /** Blank, null and unrecognisable input all decode to no tags. Order is the column's. */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
