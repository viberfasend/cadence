package de.andi1984.cadence.domain.model

import java.time.Instant

/**
 * A band inside one project's task list — Todoist's "section", and the same idea here.
 *
 * Deliberately *not* a project with a `parentId`. A subproject is a place of its own: it has a
 * screen, it carries a colour, it shows up in the project tree and a task filed under it is not
 * in the parent's list. A section is a heading in a list — the tasks under it are still the
 * project's, they still answer to its filters, and the section only says where in the list they
 * are drawn. Reusing subprojects for it would have spent the one level of nesting the app allows
 * and made every section a project the user has to look at somewhere else.
 *
 * A section belongs to exactly one project and never nests. A task's [Task.sectionId] therefore
 * only means anything alongside its [Task.projectId], which is why moving a task to another
 * project clears it.
 */
data class Section(
    /** Blank until [de.andi1984.cadence.data.CadenceRepository] mints a UUIDv7 — ids are minted
     *  by the repository, never by storage (ADR 0001, decision 4). */
    val id: String = "",
    /** The project this section groups. Sections are never nested and never project-less. */
    val projectId: String,
    val name: String,
    val sortOrder: Int = 0,
    /** Last write, local or merged in. The merge resolves conflicts by this. */
    val updatedAt: Instant = Instant.EPOCH,
    /** Tombstone — see [Task.deletedAt]. */
    val deletedAt: Instant? = null,
)
