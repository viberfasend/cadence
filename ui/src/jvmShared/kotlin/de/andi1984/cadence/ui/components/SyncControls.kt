package de.andi1984.cadence.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.data.sync.SyncStatus
import de.andi1984.cadence.ui.format.syncFailureText
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.Instant

/**
 * What a shell hands the four top-level lists so sync can show itself there (ADR 0002, decision
 * 13). The default is "nothing at all", which is what a screen rendered outside a shell gets.
 *
 * The two flags are the one difference between the platforms: Android has a pull gesture and
 * does not want a fourth icon beside sort and search, the desktop has neither a pull gesture nor
 * a discoverable `Ctrl`+`R` and therefore does. Everything else — the indicator, its four looks,
 * where a tap goes — is the same code on both.
 */
data class SyncControls(
    /** Runs a round by hand: the pull, the button, the shortcut. */
    val onRefresh: () -> Unit = {},
    /** Where a tap on the indicator goes — Settings spells the state out in words. */
    val onOpenSettings: () -> Unit = {},
    /** Desktop only. */
    val showRefreshControl: Boolean = false,
    /** Android only. */
    val pullToRefresh: Boolean = false,
)

/**
 * Whether there is an account on this device to sync with — the one distinction every surface
 * outside Settings draws. Signed out and unconfigured both answer no; Settings alone tells the
 * two apart, because only it has something different to say about each.
 */
val SyncStatus.hasAccount: Boolean
    get() = this !is SyncStatus.SignedOut && this !is SyncStatus.Unconfigured

/**
 * The status indicator, plus the desktop's refresh button — [ScreenHeader]'s `actions` slot on
 * Today, Upcoming, Inbox and Projects.
 *
 * Four looks: absent (signed out), a spinner (syncing), a plain icon (idle), and an error tint
 * for a round that failed, a device that is offline, or one that last synced more than a day ago.
 * Staleness is a tint and never a dialog — an app unopened for a week is not an emergency.
 *
 * Signed out this renders nothing whatsoever, not a greyed-out icon: a fresh install has no
 * account and should not advertise machinery behind one. A build with no sync endpoint at all
 * ([SyncStatus.Unconfigured]) is the same case, only permanent.
 */
@Composable
fun SyncActions(status: SyncStatus, controls: SyncControls) {
    if (!status.hasAccount) return

    IconButton(onClick = controls.onOpenSettings) {
        when (status) {
            is SyncStatus.Syncing -> {
                // A progress indicator carries no description of its own, and this one is the
                // whole content of the button, so the button says what it is instead.
                val label = stringResource(Res.string.sync_status_syncing)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = label },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is SyncStatus.Failed -> Icon(
                imageVector = AppIcons.SyncProblem,
                contentDescription = stringResource(Res.string.sync_status_failed),
                tint = MaterialTheme.colorScheme.error,
            )

            is SyncStatus.Idle ->
                if (status.lastSyncedAt.isStale()) {
                    Icon(
                        imageVector = AppIcons.SyncProblem,
                        contentDescription = stringResource(Res.string.sync_status_stale),
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.SyncIdle,
                        contentDescription = stringResource(Res.string.sync_status_idle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            // Both returned above; the compiler wants the branches.
            is SyncStatus.SignedOut, is SyncStatus.Unconfigured -> Unit
        }
    }

    if (controls.showRefreshControl) {
        IconButton(onClick = controls.onRefresh) {
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = stringResource(Res.string.action_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Wraps a top-level list in the pull gesture, where the shell asked for one.
 *
 * Off — the desktop, and any screen signed out — this is a bare [Box], so no screen has to know
 * which shell it is drawn by and none of them grows an inert gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(
    status: SyncStatus,
    controls: SyncControls,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!controls.pullToRefresh || !status.hasAccount) {
        Box(modifier = modifier) { content() }
        return
    }
    PullToRefreshBox(
        isRefreshing = status is SyncStatus.Syncing,
        onRefresh = controls.onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * Raises a snackbar for every round that fails, `OFFLINE` included (ADR 0002, decision 14).
 *
 * A new failure replaces the one on screen rather than queueing behind it — eight failed rounds
 * in a tunnel cost one snackbar's worth of screen time instead of minutes of them. `collectLatest`
 * is what does that: it cancels the previous [SnackbarHostState.showSnackbar], which takes its
 * snackbar down with it, and the explicit dismiss covers one already on its way out.
 *
 * There is no **Retry** action on purpose. The next trigger already is one.
 */
@Composable
fun SyncFailureSnackbar(failures: Flow<SyncFailure>, hostState: SnackbarHostState) {
    // Resources are read here, not inside the effect: `stringResource` is a composable, and the
    // effect that shows the snackbar is not.
    val messages = SyncFailure.entries.associateWith { syncFailureText(it) }

    LaunchedEffect(failures, hostState, messages) {
        failures.collectLatest { failure ->
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(
                message = messages.getValue(failure),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        }
    }
}

/** Idle for longer than this reads as an error rather than as idle. */
private val STALE_AFTER: Duration = Duration.ofHours(24)

/**
 * Whether the last round is old enough to worry about.
 *
 * A device that has never synced is not stale: signing in runs a round straight away, so the
 * only way to be signed in with no timestamp at all is to be in the middle of the first one.
 */
private fun Instant?.isStale(): Boolean =
    this != null && Duration.between(this, Instant.now()) > STALE_AFTER
