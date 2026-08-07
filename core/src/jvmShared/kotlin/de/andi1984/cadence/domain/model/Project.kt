package de.andi1984.cadence.domain.model

/**
 * Projects nest exactly one level deep: a top-level project may have subprojects,
 * subprojects may not.
 */
data class Project(
    val id: Long = 0L,
    val name: String,
    val colorHex: String = "#006A60",
    val parentId: Long? = null,
    val sortOrder: Int = 0,
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
