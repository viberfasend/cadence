package de.andi1984.cadence.widget

import de.andi1984.cadence.AppContainer
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.taskList
import kotlinx.coroutines.flow.first

/**
 * Enough of a [CadenceUiState] for a widget to call [taskList] on.
 *
 * A widget has no ViewModel — it is woken to render and then gone, so there is nothing for a
 * `StateFlow` to outlive — but the *derivations* it needs hang off `CadenceUiState`. Building one
 * by hand from the repository is what lets a widget ask the app "what does Today show?" rather
 * than working it out again and slowly disagreeing.
 *
 * Only `tasks` and `settings` are filled, because only those two are read by the views the
 * widgets name: `TaskView.Today` and `TaskView.Inbox` derive from the task list, the
 * `showCompleted` flag and the sort mode, and nothing else. A widget scoped to a project would
 * be reaching for `TaskView.Project`, which bands by section — that one has to fill `projects`
 * and `sections` here first, and would be wrong rather than merely incomplete without them.
 */
internal suspend fun AppContainer.widgetUiState(): CadenceUiState = CadenceUiState(
    tasks = repository.tasks.first(),
    settings = settingsStore.state.value,
)
