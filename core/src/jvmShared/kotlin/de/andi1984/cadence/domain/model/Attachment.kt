package de.andi1984.cadence.domain.model

import java.time.Instant

enum class AttachmentKind { LINK, FILE }

/**
 * One file or link filed on a task.
 *
 * One-to-many via [taskId], not a join table: the *blob* a FILE points at is already shared by
 * content address (`data/BlobStore.kt`), and the attachment is a per-task fact — its own name,
 * order and creation time — that a join table would have to carry anyway while turning every
 * delete into two steps.
 *
 * No `IMAGE` kind: that would be a denormalised copy of `mimeType.startsWith("image/")` that can
 * disagree with it. Thumbnails branch on the mime prefix instead ([isImage]).
 *
 * No Android imports, so a future ranking or matching function can reach this from `domain/` the
 * way `RecurrenceEngine` reaches [Task].
 */
data class Attachment(
    /** Blank until [de.andi1984.cadence.data.CadenceRepository] mints a UUIDv7 for a new
     *  attachment — ids are minted by the caller, never assigned by storage (ADR 0001,
     *  decision 4), the same rule as [Task.id]/[Project.id]. */
    val id: String = "",
    val taskId: String,
    val kind: AttachmentKind,
    /** File name, or the link's title. Never null — a row always has something to draw. */
    val name: String,
    /** The blob's media type, or "text/uri-list" for a LINK. */
    val mimeType: String,
    /** Lowercase hex SHA-256 of the bytes — the blob's only name. Null for a LINK. */
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    /** The URL for a LINK, null for a FILE. */
    val url: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
