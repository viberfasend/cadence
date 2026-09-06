package de.andi1984.cadence.ui.undo

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.SnackbarMessage
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.undo_items_deleted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * A destructive action the user just took, held back from the database for [UndoSlot]'s window so
 * an undo costs no write at all — it only cancels the deferred job that would have committed it.
 */
sealed class UndoAction {
    /** Every id the commit will tombstone, so the pending set can hide exactly those rows. */
    abstract val ids: Set<String>

    /** Deleting a task tombstones it and its subtasks together. */
    data class DeleteTask(val task: Task, val subtasks: List<Task>) : UndoAction() {
        /** Every id the commit will tombstone — the row and its steps. */
        override val ids: Set<String> = setOf(task.id) + subtasks.map { it.id }
    }

    /** Deleting a project tombstones it and (optionally) its subprojects and tasks. */
    data class DeleteProject(
        val project: Project,
        val tasks: List<Task>,
        val deleteTasks: Boolean,
    ) : UndoAction() {
        /** Every id the commit will tombstone. Tasks are only included when [deleteTasks] is
         *  set, the same way [de.andi1984.cadence.data.CadenceRepository.deleteProject] only
         *  tombstones them then. */
        override val ids: Set<String> =
            if (deleteTasks) setOf(project.id) + tasks.map { it.id } else setOf(project.id)
    }

    /**
     * The Settings danger zone: every task and every project at once.
     *
     * The ids are what the user saw on screen when they confirmed, held so the rows can be hidden
     * for the undo window and un-hidden again by an undo. The commit itself wipes whatever is
     * stored at the moment it runs, not this list — a row merged in from a pull during those few
     * seconds goes too, which is what "delete everything" has to mean for the wipe to be the same
     * on every device.
     */
    data class DeleteEverything(
        val taskIds: List<String>,
        val projectIds: List<String>,
    ) : UndoAction() {
        override val ids: Set<String> = (taskIds + projectIds).toSet()
    }
}

/**
 * The undo/deferred-delete machine (#114).
 *
 * An [offer]ed action is held back from the database for [window]: it hides its [hiddenIds] at
 * once and shows a snackbar with an **Undo** button, but the real write — [commit] — does not run
 * until the window elapses, so an [undo] inside it costs no transaction at all, only the
 * cancellation of the deferred job. `CadenceViewModel` owns *what* [commit] does — dispatching to
 * the repository and cancelling reminders, with the repository write ticking `localWrites` (and
 * so arming sync) on its own — and hands it in as a lambda; this class owns *when*, which is the
 * two rules that follow from there being one pending action but possibly more than one set of
 * hidden ids:
 *
 * - **[undo] subtracts only its own action's ids, never the whole set.** A second [offer] settles
 *   a *prior* action out of band — the user moved on — and that commit may still be in flight with
 *   its own ids in [hiddenIds]. Clearing the whole set would flash those rows back into every list
 *   until the write caught up and took them away again. Nothing rescues a settled action; that is
 *   what settling it meant.
 * - **An informational message never displaces a live undo — it queues** ([show]). The two are not
 *   equals: a validation message is repeatable feedback about a form still on screen, while the
 *   undo is a five-second, one-time chance to take an action back. Overwriting it would take that
 *   chance away silently while the action still committed.
 */
class UndoSlot(
    private val scope: CoroutineScope,
    private val window: Duration,
    private val commit: suspend (UndoAction) -> Unit,
) {
    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Ids hidden while their destructive write is held back.
     *
     * The caller is expected to filter these out of every list it draws the moment they land
     * here — see [offer] — even though no database transaction has happened yet.
     */
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    private val _snackbar = MutableStateFlow<SnackbarMessage?>(null)

    /** The message currently on offer: an undoable one from [offer], an informational one from
     *  [show], or null when the slot is empty. */
    val snackbar: StateFlow<SnackbarMessage?> = _snackbar.asStateFlow()

    /** The action [pendingJob] will commit when its window elapses, captured so a second [offer]
     *  can settle it out of band rather than leave its ids hidden forever. */
    private var pendingAction: UndoAction? = null
    private var pendingJob: Job? = null

    /**
     * An informational message that arrived while the snackbar was carrying a live undo, shown
     * once that undo resolves — the class doc's second rule. Only ever one is held: a second
     * informational message replaces it, because the newest is the one the user just caused.
     */
    private var queuedMessage: SnackbarMessage? = null

    /**
     * Offers an undo for [action]: hides its ids at once, shows a counted snackbar with an
     * **Undo** button, and arms [commit] to run after [window].
     *
     * A second offer while one is already pending commits the first one immediately rather than
     * racing two deferred jobs against the same data — the user moved on, so the previous action
     * is settled.
     */
    fun offer(action: UndoAction, count: Int) {
        // Settle anything still pending before starting a new one: cancel its timer and run the
        // commit out of band, so its ids are truly gone rather than left hidden forever.
        val prior = pendingAction
        pendingJob?.cancel()
        if (prior != null) {
            scope.launch { settle(prior) }
        }

        pendingAction = action
        _hiddenIds.value = _hiddenIds.value + action.ids
        _snackbar.value = SnackbarMessage.Counted(
            plural = Res.plurals.undo_items_deleted,
            count = count,
            args = listOf(count),
            undoAction = action,
        )
        pendingJob = scope.launch {
            delay(window.toMillis())
            settle(action)
        }
    }

    /**
     * Runs [commit] for [action], then unhides its ids and clears the snackbar — but only if it
     * is still the one showing this action's undo (see [offer]'s out-of-band settle, which must
     * not clobber a snackbar a later offer already replaced).
     */
    private suspend fun settle(action: UndoAction) {
        if (pendingAction === action) pendingAction = null
        commit(action)
        _hiddenIds.value = _hiddenIds.value - action.ids
        if (_snackbar.value?.undoAction === action) {
            clearSnackbar()
        }
    }

    /**
     * Undo. Cancels the deferred job and unhides the ids it was holding back — they were never
     * written, so they reappear from whatever flow was feeding them, and there is no database
     * transaction at all. This is the whole point of offsetting the write by the window.
     *
     * **Only this action's ids come back** — see the class doc's first rule.
     */
    fun undo() {
        val action = pendingAction
        pendingJob?.cancel()
        pendingJob = null
        pendingAction = null
        _hiddenIds.value = _hiddenIds.value - action?.ids.orEmpty()
        clearSnackbar()
    }

    /** Dismiss the current snackbar. Committing a pending action is left to its own timer —
     *  dismissing the banner does not rush the write, it only stops showing it. */
    fun dismiss() {
        clearSnackbar()
    }

    /**
     * Raises [message], or holds it back while the slot carries a live undo (see [queuedMessage])
     * — the class doc's second rule. [offer] writes [snackbar] directly, because an undo is what
     * an informational message defers *to*, never the other way round.
     */
    fun show(message: SnackbarMessage) {
        if (_snackbar.value?.undoAction != null) {
            queuedMessage = message
        } else {
            _snackbar.value = message
        }
    }

    /** Empties the slot, and lets whatever was waiting for it through. */
    private fun clearSnackbar() {
        _snackbar.value = queuedMessage
        queuedMessage = null
    }
}
