package de.andi1984.cadence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.andi1984.cadence.CadenceApplication

/**
 * Gives [CadenceViewModel] the two things Android's lifecycle offers and `:ui` deliberately does
 * not depend on: a scope that outlives a rotation, and a moment to stop.
 *
 * `viewModelScope` is cancelled for us when this host is cleared, which is exactly what
 * [CadenceViewModel.close] does elsewhere — so there is nothing to override here.
 */
class CadenceViewModelHost(application: CadenceApplication) : ViewModel() {

    private val container = application.container

    val viewModel = CadenceViewModel(
        repository = container.repository,
        settingsStore = container.settingsStore,
        reminderScheduler = container.reminderScheduler,
        backupGateway = container.backupIo,
        syncEngine = container.syncEngine,
        scope = viewModelScope,
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApplication
                CadenceViewModelHost(application)
            }
        }
    }
}
