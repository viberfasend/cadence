package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.ui.platform.AutoBackupController
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
 * The desktop's answer to `:app-android`'s `AutoBackupSync`: same three moments (open, change,
 * leave) and the same "never read back a file we wrote ourselves" rule
 * (`AutoBackupPolicy.shouldImport`), over [DesktopBackupIo] instead of the SAF. There is no uri
 * grant to keep alive here — a path on disk needs nothing renewed — so [enable] just switches
 * the setting on. Superseded in phase 6 by the per-device sync folder, same as Android's.
 */
class DesktopAutoBackupSync(
    private val backupIo: DesktopBackupIo,
    private val settingsStore: SettingsStore,
    repository: CadenceRepository,
    private val scope: CoroutineScope,
) : AutoBackupController {

    private val mutex = Mutex()

    private val _failure = MutableStateFlow<BackupFailure?>(null)
    override val failure: StateFlow<BackupFailure?> = _failure.asStateFlow()

    init {
        scope.launch { watchForChanges(repository) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchForChanges(repository: CadenceRepository) {
        combine(repository.tasks, repository.projects) { _, _ -> Unit }
            .drop(1)
            .debounce(WRITE_DELAY_MS)
            .collect { exportNow() }
    }

    /** Called once the window is up. */
    override fun onAppForegrounded() {
        scope.launch { importIfNewer() }
    }

    /** Called as the window is about to close. */
    override fun onAppBackgrounded() {
        scope.launch { exportNow() }
    }

    override fun enable(target: BackupTarget) {
        _failure.value = null
        settingsStore.enableAutoBackup(target.value)
    }

    override fun disable() {
        _failure.value = null
        settingsStore.disableAutoBackup()
    }

    override fun declineOffer() = settingsStore.markAutoBackupOffered()

    override fun noteManualBackup(target: BackupTarget, outcome: BackupOutcome) {
        if (target.value != settingsStore.state.value.autoBackup.fileUri) return
        when (outcome) {
            is BackupOutcome.Exported -> settingsStore.recordAutoBackupSync(outcome.exportedAt)
            is BackupOutcome.Imported -> outcome.exportedAt?.let(settingsStore::recordAutoBackupSync)
            is BackupOutcome.Failed -> Unit
        }
    }

    /** Launched from the window's close handler and joined before the process exits, so the
     *  write that starts as the user quits finishes rather than racing the JVM shutting down. */
    fun flushBeforeExit(): Job = scope.launch { exportNow() }

    private suspend fun importIfNewer() {
        val target = activeTarget() ?: return
        val lastSyncedAt = settingsStore.state.value.autoBackup.lastSyncedAt
        mutex.withLock {
            when (val outcome = backupIo.importIfNewer(target, lastSyncedAt)) {
                null -> Unit
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
        val target = activeTarget() ?: return
        mutex.withLock {
            when (val outcome = backupIo.export(target)) {
                is BackupOutcome.Exported -> {
                    _failure.value = null
                    settingsStore.recordAutoBackupSync(outcome.exportedAt)
                }

                is BackupOutcome.Failed -> _failure.value = outcome.reason
                is BackupOutcome.Imported -> Unit
            }
        }
    }

    private fun activeTarget(): BackupTarget? = settingsStore.state.value.autoBackup
        .takeIf { it.isActive }
        ?.fileUri
        ?.let(::BackupTarget)

    private companion object {
        const val WRITE_DELAY_MS = 2_000L
    }
}
