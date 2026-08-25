package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Why the last round did not finish, in words.
 *
 * Two screens ask for the same sentence now — the Settings section states it in place, and the
 * snackbar every failed round raises says it wherever the user happens to be (ADR 0002, decision
 * 14) — so the wording lives here rather than beside either of them.
 */
@Composable
fun syncFailureText(reason: SyncFailure): String = when (reason) {
    SyncFailure.OFFLINE -> stringResource(Res.string.settings_sync_failed_offline)
    SyncFailure.SESSION_EXPIRED -> stringResource(Res.string.settings_sync_failed_session)
    SyncFailure.SERVER -> stringResource(Res.string.settings_sync_failed_server)
}
