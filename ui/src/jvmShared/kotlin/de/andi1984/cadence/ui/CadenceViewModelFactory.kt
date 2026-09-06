package de.andi1984.cadence.ui

import de.andi1984.cadence.data.CadenceCore
import de.andi1984.cadence.ui.platform.AttachmentOpener
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.ReminderScheduler
import de.andi1984.cadence.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope

/**
 * The platform ports [CadenceViewModel] needs beyond what [CadenceCore] already builds —
 * everything in `ui/platform/Ports.kt` plus the [SettingsStore] port, one instance per shell.
 *
 * A plain value rather than four separate parameters on [cadenceViewModel]: the two shells
 * construct one of these next to their `AppContainer`'s adapters and hand it over whole.
 */
class ViewModelAdapters(
    val settingsStore: SettingsStore,
    val reminderScheduler: ReminderScheduler,
    val backupGateway: BackupGateway,
    val attachmentOpener: AttachmentOpener,
)

/**
 * Builds the one [CadenceViewModel] the app has, over [core]'s repository and sync engine plus
 * [adapters]' platform ports — written once so the seven-argument constructor is not copied into
 * every shell that needs a ViewModel.
 *
 * [scope] is the caller's, not [core]'s: it is a rotation-surviving `viewModelScope` on Android
 * and a plain scope `main()` owns on the desktop, and neither is the application scope
 * [CadenceCore.applicationScope] the sync engine and the blob sweep run on.
 */
fun cadenceViewModel(
    core: CadenceCore,
    adapters: ViewModelAdapters,
    scope: CoroutineScope,
): CadenceViewModel = CadenceViewModel(
    repository = core.repository,
    settingsStore = adapters.settingsStore,
    reminderScheduler = adapters.reminderScheduler,
    backupGateway = adapters.backupGateway,
    attachmentOpener = adapters.attachmentOpener,
    syncEngine = core.syncEngine,
    scope = scope,
)
