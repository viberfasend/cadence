package de.andi1984.cadence.domain.model

import java.time.Instant

/**
 * Projects nest exactly one level deep: a top-level project may have subprojects,
 * subprojects may not.
 */
data class Project(
    /** Blank until [de.andi1984.cadence.data.CadenceRepository] mints a UUIDv7 for a new
     *  project — ids are no longer assigned by storage (ADR 0001, decision 4). */
    val id: String = "",
    val name: String,
    val colorHex: String = "#006A60",
    val parentId: String? = null,
    val sortOrder: Int = 0,
    /** Last write, local or merged in. The phase-6 merge engine resolves conflicts by this. */
    val updatedAt: Instant = Instant.EPOCH,
    /** Tombstone, unused until phase 6 — see [Task.deletedAt]. */
    val deletedAt: Instant? = null,
) {
    val isSubproject: Boolean get() = parentId != null
}

/** A top-level project together with its subprojects, ready to render. */
data class ProjectTreeNode(
    val project: Project,
    val children: List<Project>,
)

/** Builds the one-level-deep tree, dropping subprojects whose parent no longer exists. */
fun List<Project>.toTree(): List<ProjectTreeNode> {
    val roots = filter { it.parentId == null }.sortedBy { it.sortOrder }
    val byParent = filter { it.parentId != null }.groupBy { it.parentId }
    return roots.map { root ->
        ProjectTreeNode(root, byParent[root.id].orEmpty().sortedBy { it.sortOrder })
    }
}

/** "Home / Finance" — the label used on task rows and the detail screen. */
fun projectPath(project: Project?, all: List<Project>): String? {
    if (project == null) return null
    val parent = project.parentId?.let { id -> all.firstOrNull { it.id == id } }
    return if (parent == null) project.name else "${parent.name} / ${project.name}"
}
