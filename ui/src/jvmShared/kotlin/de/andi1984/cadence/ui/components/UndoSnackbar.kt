package de.andi1984.cadence.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import de.andi1984.cadence.ui.SnackbarMessage
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.undo_action
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Raises the snackbar for an undoable (or informational) message the ViewModel published.
 *
 * The delete itself is held back from the database for the undo window (see
 * `CadenceViewModel.offerUndo`), so an **Undo** tap here costs no database transaction at all —
 * it only cancels the deferred delete job. That is the whole point of offsetting the write by the
 * window, and it is why this snackbar shows the action only while [SnackbarMessage.undoAction] is
 * set: once the window elapses the write is committed and the message is cleared.
 *
 * A new message replaces the one on screen rather than queueing behind it — the same trade-off as
 * `SyncFailureSnackbar`: ten deletes in a hurry cost one banner's worth of screen time, and the
 * previous one's write is committed out of band by the ViewModel.
 *
 * @param onUndo called when the user taps the action; the ViewModel cancels the pending job.
 * @param onDismiss called when the banner is dismissed (swipe, timeout) with no undo — the
 *   write is left to its own timer, so dismissing does not rush it.
 */
@Composable
fun UndoSnackbar(
    message: SnackbarMessage?,
    hostState: SnackbarHostState,
    onUndo: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    // Resources are read here, not inside the effect: `stringResource`/`pluralStringResource` are
    // composables, and the effect that shows the snackbar is not.
    val actionLabel = stringResource(Res.string.undo_action)
    val resolved = message?.let { resolve(it) }

    LaunchedEffect(message, resolved, hostState, actionLabel) {
        if (resolved == null) return@LaunchedEffect
        // A fresh message replaces whatever is showing, the same way a fresh sync failure does.
        hostState.currentSnackbarData?.dismiss()
        val hasUndo = message.undoAction != null
        val result = hostState.showSnackbar(
            message = resolved,
            actionLabel = if (hasUndo) actionLabel else null,
            withDismissAction = !hasUndo,
            // An undoable delete stays long enough to read and tap; an informational one is brief.
            duration = if (hasUndo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> onDismiss()
        }
    }
}

/** Resolves a [SnackbarMessage] to a ready-made string, picking the plural or plain form. */
@Composable
private fun resolve(message: SnackbarMessage): String = when (message) {
    is SnackbarMessage.Text -> stringResource(message.text, *message.args.toTypedArray())
    is SnackbarMessage.Counted ->
        pluralStringResource(message.plural, message.count, *message.args.toTypedArray())
}
