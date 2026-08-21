package de.andi1984.cadence.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import java.time.LocalDate

/**
 * What a drag *means*, decided in plain Kotlin.
 *
 * This half of the drag kernel touches no Compose runtime and holds no state: a payload, a target
 * and the current [CadenceUiState] go in, an intent comes out, and every rule about what may be
 * dropped where lives in [resolveDrop] rather than being repeated per screen. `DragAndDrop.kt`
 * next door does the gesture, the ghost and the hover highlight; it decides nothing.
 *
 * Why not `Modifier.dragAndDropSource`/`Target`: its payload is a `ClipEntry` wrapping an AWT
 * `Transferable` on the desktop and a `ClipData` on Android, and building one needs an
 * `expect`/`actual` seam this module does not have — `jvmShared` is a single source set feeding
 * both targets. Every drag here is in-process and carries a domain object, none of them want a
 * MIME type, and the platform API has no notion of the insertion caret that is most of what these
 * drags have to show. See `docs/adr/0003-desktop-interaction-model.md`.
 */
sealed interface DragPayload {
    /** The row being dragged, for the "you cannot drop something on itself" rule. */
    val id: String

    data class TaskDrag(val task: Task) : DragPayload {
        override val id: String get() = task.id
    }

    data class ProjectDrag(val project: Project) : DragPayload {
        override val id: String get() = project.id
    }

    data class SectionDrag(val section: Section) : DragPayload {
        override val id: String get() = section.id
    }
}

/** One of the app's hand-ordered lists — the bucket a [DropTarget.Between] inserts into. */
sealed interface OrderedList {
    /** A project's band, or the Inbox (`projectId == null`), or a day in Today. */
    data class Tasks(val projectId: String?, val sectionId: String? = null) : OrderedList

    /** The projects directly under [parentId] — null for the root list. */
    data class Projects(val parentId: String?) : OrderedList

    /** One project's bands. */
    data class Sections(val projectId: String) : OrderedList
}

/**
 * A place something can be dropped, as registered by the composable that draws it.
 *
 * A zone registers the target it *would* accept and [resolveDrop] decides whether this particular
 * payload may land there, so a row does not have to know which kinds of thing exist.
 */
sealed interface DropTarget {
    /** A project row, or the Inbox row (`projectId == null`). */
    data class IntoProject(val projectId: String?) : DropTarget

    /** A section band's header or body. `sectionId == null` is the project's ungrouped band. */
    data class IntoSection(val projectId: String, val sectionId: String?) : DropTarget

    /** A day header in Upcoming, or a "no date" affordance (`date == null`). */
    data class OntoDate(val date: LocalDate?) : DropTarget

    /** The gap between two rows of [list]: the payload lands at [index] of [orderedIds].
     *
     *  [orderedIds] is the list as it is currently drawn, dragged row included — the zone knows
     *  it and the kernel does not, and carrying it here is what lets the intent be self-contained
     *  instead of every screen recomputing the same list on drop. */
    data class Between(val list: OrderedList, val index: Int, val orderedIds: List<String>) : DropTarget
}

/** What the app should actually do — the only thing a shell has to interpret. */
sealed interface DropIntent {

    /** File a task under a project and a band. Both are absolute: null means Inbox / ungrouped. */
    data class MoveTask(
        val taskId: String,
        val projectId: String?,
        val sectionId: String?,
    ) : DropIntent

    data class RescheduleTask(val taskId: String, val date: LocalDate?) : DropIntent

    /** Nest a project under [parentId], or (null) lift it back to the root list. */
    data class NestProject(val projectId: String, val parentId: String?) : DropIntent

    /**
     * Write a task list's order.
     *
     * [move] is set when the row came from another list — a drag from one band into a position in
     * the next is one gesture and has to be one intent, or the row is filed and then ordered
     * against a list it was not in yet.
     */
    data class ReorderTasks(val orderedIds: List<String>, val move: MoveTask? = null) : DropIntent

    data class ReorderProjects(val parentId: String?, val orderedIds: List<String>) : DropIntent

    data class ReorderSections(val projectId: String, val orderedIds: List<String>) : DropIntent

    /** Nothing would change, or the drop is not allowed. A zone resolving to this is not
     *  highlighted, so an impossible drag never *looks* possible. */
    data object Rejected : DropIntent
}

/**
 * The one place that decides what a drop does.
 *
 * A no-op is [DropIntent.Rejected] on purpose rather than a write that changes nothing: the hover
 * highlight is driven by this, and a row lighting up for a drop that does nothing is a lie about
 * what will happen.
 */
fun resolveDrop(payload: DragPayload, target: DropTarget, state: CadenceUiState): DropIntent =
    when (payload) {
        is DragPayload.TaskDrag -> resolveTaskDrop(payload.task, target)
        is DragPayload.ProjectDrag -> resolveProjectDrop(payload.project, target, state)
        is DragPayload.SectionDrag -> resolveSectionDrop(payload.section, target)
    }

private fun resolveTaskDrop(task: Task, target: DropTarget): DropIntent =
    when (target) {
        is DropTarget.IntoProject -> when {
            task.projectId == target.projectId -> DropIntent.Rejected
            // A band belongs to one project, so a task that changes project loses its heading —
            // the same rule `CadenceRepository.moveToProject` applies.
            else -> DropIntent.MoveTask(task.id, target.projectId, sectionId = null)
        }

        is DropTarget.IntoSection -> when {
            task.projectId == target.projectId && task.sectionId == target.sectionId ->
                DropIntent.Rejected
            // Dropping on a band of a project the task is not in is a move *and* a grouping, not
            // a rejection: the band names its project, so the gesture is unambiguous.
            else -> DropIntent.MoveTask(task.id, target.projectId, target.sectionId)
        }

        is DropTarget.OntoDate ->
            if (task.dueDate == target.date) DropIntent.Rejected
            else DropIntent.RescheduleTask(task.id, target.date)

        is DropTarget.Between -> {
            val list = target.list
            if (list !is OrderedList.Tasks) {
                DropIntent.Rejected
            } else {
                val move = if (task.projectId != list.projectId || task.sectionId != list.sectionId) {
                    DropIntent.MoveTask(task.id, list.projectId, list.sectionId)
                } else {
                    null
                }
                val ordered = insertAt(target.orderedIds, task.id, target.index)
                if (ordered == null) DropIntent.Rejected else DropIntent.ReorderTasks(ordered, move)
            }
        }
    }

private fun resolveProjectDrop(
    project: Project,
    target: DropTarget,
    state: CadenceUiState,
): DropIntent = when (target) {
    // A project dropped on a project row nests it. `IntoProject(null)` — the Inbox row — lifts it
    // back to the root list instead, which is the only way to un-nest by dragging.
    is DropTarget.IntoProject -> when {
        target.projectId == null ->
            if (project.parentId == null) DropIntent.Rejected
            else DropIntent.NestProject(project.id, null)

        else -> nestOrReject(project, target.projectId, state)
    }

    is DropTarget.Between -> {
        val list = target.list
        if (list !is OrderedList.Projects) {
            DropIntent.Rejected
        } else if (project.parentId != list.parentId) {
            // Dropping a subproject into the root list between two rows is a *move*, and the
            // ordering that follows it is a second gesture. Reordering it here would leave it
            // nested but numbered among rows it is not beside.
            DropIntent.Rejected
        } else {
            val ordered = insertAt(target.orderedIds, project.id, target.index)
            if (ordered == null) DropIntent.Rejected
            else DropIntent.ReorderProjects(list.parentId, ordered)
        }
    }

    is DropTarget.IntoSection, is DropTarget.OntoDate -> DropIntent.Rejected
}

private fun resolveSectionDrop(section: Section, target: DropTarget): DropIntent {
    val between = target as? DropTarget.Between ?: return DropIntent.Rejected
    val list = between.list as? OrderedList.Sections ?: return DropIntent.Rejected
    if (list.projectId != section.projectId) return DropIntent.Rejected
    val ordered = insertAt(between.orderedIds, section.id, between.index)
        ?: return DropIntent.Rejected
    return DropIntent.ReorderSections(list.projectId, ordered)
}

/**
 * Nesting, with the three rules the model does not enforce itself.
 *
 * Projects nest exactly one level (`CadenceUiState.nestingCandidates` states the same rule for the
 * dialog), so a project that already has subprojects cannot become one, and neither can anything
 * dropped on a project that is already nested. A cycle is ruled out too — the repository would
 * refuse it, but a target that lights up and then does nothing is the failure being avoided here.
 */
private fun nestOrReject(project: Project, parentId: String, state: CadenceUiState): DropIntent =
    when {
        parentId == project.id -> DropIntent.Rejected
        project.parentId == parentId -> DropIntent.Rejected
        state.subprojects(project.id).isNotEmpty() -> DropIntent.Rejected
        state.project(parentId)?.parentId != null -> DropIntent.Rejected
        else -> DropIntent.NestProject(project.id, parentId)
    }

/**
 * [orderedIds] with [movedId] taken out and put back at [index], or null when that is where it
 * already is.
 *
 * [index] counts gaps in the list *as drawn* — index 2 of `[a, b, c]` is the gap between `b` and
 * `c` — so a row moving down has to have its own slot removed before it lands, which is what
 * makes dropping a row into the gap just below itself a no-op rather than an off-by-one.
 */
internal fun insertAt(orderedIds: List<String>, movedId: String, index: Int): List<String>? {
    val from = orderedIds.indexOf(movedId)
    val without = if (from >= 0) orderedIds - movedId else orderedIds
    val to = when {
        from < 0 -> index.coerceIn(0, without.size)
        index > from -> (index - 1).coerceIn(0, without.size)
        else -> index.coerceIn(0, without.size)
    }
    if (from == to) return null
    return without.toMutableList().apply { add(to, movedId) }
}

/** A registered drop zone: where it is on screen, and what it would accept. */
data class DropZone(
    val key: Any,
    val bounds: Rect,
    val target: DropTarget,
)

/**
 * The zone under the pointer that would actually do something.
 *
 * **Innermost wins**, measured by area: a section band sits inside a project pane, and the band is
 * what the user is pointing at. A zone whose intent is [DropIntent.Rejected] is skipped rather
 * than blocking the one behind it — dragging a project across a task row must still reach the
 * project pane underneath.
 */
fun hitTest(
    zones: Collection<DropZone>,
    position: Offset,
    payload: DragPayload,
    state: CadenceUiState,
): Pair<DropZone, DropIntent>? = zones
    .asSequence()
    .filter { it.bounds.contains(position) }
    .sortedBy { it.bounds.width * it.bounds.height }
    .mapNotNull { zone ->
        val intent = resolveDrop(payload, zone.target, state)
        if (intent is DropIntent.Rejected) null else zone to intent
    }
    .firstOrNull()
