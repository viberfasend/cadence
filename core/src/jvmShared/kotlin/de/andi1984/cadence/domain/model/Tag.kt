package de.andi1984.cadence.domain.model

import java.time.Instant

/**
 * A cross-cutting label. Orthogonal to [Project], which is where a task *lives*.
 *
 * A task has one project and any number of tags, and that asymmetry is the whole point: `@errand`
 * and `@waiting` describe work filed under half a dozen different projects, and a hierarchy that
 * nests one level cannot say both "this is Home / Finance work" and "this is something I am
 * blocked on" at once.
 *
 * Deliberately *not* a second project tree. A tag has no parent, holds nothing, and owns no
 * screen of its own beyond the filtered list a chip opens — deleting one takes no task with it,
 * which is exactly what makes it cheap enough to apply four of them to a row.
 *
 * **Membership does not live here.** A task carries the ids ([Task.tagIds]); this record carries
 * only the tag's identity, so renaming or recolouring one is a single write that reaches every
 * task at once and travels as one row. See [Task.tagIds] for what that costs.
 */
data class Tag(
    /** Blank until [de.andi1984.cadence.data.CadenceRepository] mints a UUIDv7 — ids are minted
     *  by the repository, never by storage (ADR 0001, decision 4). */
    val id: String = "",
    val name: String,
    val colorHex: String = "#3E6373",
    val sortOrder: Int = 0,
    /** Last write, local or merged in. The merge resolves conflicts by this. */
    val updatedAt: Instant = Instant.EPOCH,
    /** Tombstone — see [Task.deletedAt]. */
    val deletedAt: Instant? = null,
) {
    /**
     * What `@…` in the quick-add line is matched against: the name with its spaces taken out.
     *
     * A tag may be called "Deep Work"; nobody types `@Deep Work`, because the space would end the
     * token. Folding the spaces out is what makes `@deepwork` reach it without forcing tag names
     * to be single words.
     */
    val handle: String get() = name.replace(" ", "")
}

/**
 * The tag [typed] names, or null.
 *
 * Three passes, widening — exact name, name with its spaces removed, then a prefix — which is the
 * same ladder [de.andi1984.cadence.domain.parse.QuickAddParser] already walks for `#project`. All
 * three are case-insensitive: a tag is a label someone types quickly, not an identifier.
 */
fun List<Tag>.matchingHandle(typed: String): Tag? =
    firstOrNull { it.name.equals(typed, ignoreCase = true) }
        ?: firstOrNull { it.handle.equals(typed, ignoreCase = true) }
        ?: firstOrNull { it.handle.startsWith(typed, ignoreCase = true) }
