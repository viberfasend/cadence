package de.andi1984.cadence.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps one backup file in step with the app, once the user has asked for it.
 *
 * Three moments write or read that file, and they are the ones a person would name if asked
 * what "keep my backup up to date" means:
 *
 *  - **opening the app** reads the file back, but only when it is newer than the last one this
 *    device wrote (see [de.andi1984.cadence.domain.backup.AutoBackupPolicy]) — an import
 *    replaces every task and project, so re-reading the app's own last export would quietly
 *    undo whatever was added since;
 *  - **changing anything** writes it again, debounced by [WRITE_DELAY_MS] so that ticking off
 *    five tasks in a row costs one write rather than five;
 *  - **leaving the app** writes it immediately, because the debounce may still be pending.
 *
 * It runs on an application-scoped [scope] rather than a ViewModel's, so the write that starts
 * as the user leaves finishes even though the screen it started from is already gone. Every
 * operation goes through [mutex]: an import that landed halfway through an export would write
 * a file that is half of each.
 *
 * Nothing here happens without an explicit yes. With sync off, or with no file named, all of it
 * is inert and the Settings screen's manual export and import are the only way data moves.
 */
class AutoBackupSync(
    private val context: Context,
    private val backupIo: BackupIo,
    private val settingsStore: SettingsStore,
    repository: CadenceRepository,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    private val _failure = MutableStateFlow<BackupFailure?>(null)

    /**
     * Why the last automatic write or read did not happen — a file the user deleted or moved,
     * most likely. Automatic sync is invisible when it works, so it has to be visible when it
     * stops; Settings shows this under the switch.
     */
    val failure: StateFlow<BackupFailure?> = _failure.asStateFlow()

    init {
        scope.launch { watchForChanges(repository) }
    }

    @OptIn(FlowPreview::class) // debounce; settled in behaviour, still preview in the API surface
    private suspend fun watchForChanges(repository: CadenceRepository) {
        combine(repository.tasks, repository.projects) { _, _ -> Unit }
            // The first emission is just the database announcing itself at startup, not a
            // change the user made.
            .drop(1)
            .debounce(WRITE_DELAY_MS)
            .collect { exportNow() }
    }

    /** Called when the app comes to the foreground. */
    fun onAppForegrounded() {
        scope.launch { importIfNewer() }
    }

    /** Called when the app goes to the background — including on the way to being killed. */
    fun onAppBackgrounded() {
        scope.launch { exportNow() }
    }

    /**
     * Switches sync on for a file the user has just picked or exported to. The SAF grant a
     * picker hands out dies with the process, so it is made persistable here — without that,
     * sync would work until the next app start and then silently stop.
     */
    fun enable(uri: Uri) {
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!kept) {
            // Better to refuse than to promise a sync that stops at the next restart.
            _failure.value = BackupFailure.WRITE_FAILED
            settingsStore.markAutoBackupOffered()
            return
        }
        _failure.value = null
        settingsStore.enableAutoBackup(uri.toString())
    }

    fun disable() {
        _failure.value = null
        settingsStore.disableAutoBackup()
    }

    /** Records the user's answer when they turn the offer down. */
    fun declineOffer() = settingsStore.markAutoBackupOffered()

    /**
     * A manual export or import counts as a sync when it used the synced file: it is now the
     * newest thing on both sides, and the next open must not read it back as if it came from
     * somewhere else.
     */
    fun noteManualBackup(uri: Uri, outcome: BackupOutcome) {
        if (uri.toString() != settingsStore.state.value.autoBackup.fileUri) return
        when (outcome) {
            is BackupOutcome.Exported -> settingsStore.recordAutoBackupSync(outcome.exportedAt)
            is BackupOutcome.Imported -> outcome.exportedAt?.let(settingsStore::recordAutoBackupSync)
            is BackupOutcome.Failed -> Unit
        }
    }

    private suspend fun importIfNewer() {
        val uri = activeUri() ?: return
        val lastSyncedAt = settingsStore.state.value.autoBackup.lastSyncedAt
        mutex.withLock {
            when (val outcome = backupIo.importIfNewer(uri, lastSyncedAt)) {
                null -> Unit // Nothing newer on disk: the device is already up to date.
                is BackupOutcome.Failed -> _failure.value = outcome.reason
                is BackupOutcome.Imported -> {
                    _failure.value = null
                    outcome.exportedAt?.let(settingsStore::recordAutoBackupSync)
                }

                is BackupOutcome.Exported -> Unit
            }
        }
    }

    private suspend fun exportNow() {
        val uri = activeUri() ?: return
        mutex.withLock {
            when (val outcome = backupIo.export(uri)) {
                is BackupOutcome.Exported -> {
                    _failure.value = null
                    settingsStore.recordAutoBackupSync(outcome.exportedAt)
                }

                is BackupOutcome.Failed -> _failure.value = outcome.reason
                is BackupOutcome.Imported -> Unit
            }
        }
    }

    private fun activeUri(): Uri? = settingsStore.state.value.autoBackup
        .takeIf { it.isActive }
        ?.fileUri
        ?.let(Uri::parse)

    private companion object {
        /** Long enough to fold a burst of edits into one write, short enough to survive a
         *  user who ticks a task and immediately leaves — [onAppBackgrounded] covers the rest. */
        const val WRITE_DELAY_MS = 2_000L
    }
}
