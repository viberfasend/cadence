package de.andi1984.cadence.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import de.andi1984.cadence.AppContainer
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.taskList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow

/**
 * Enough of a [CadenceUiState] for a widget to call [taskList] on, **as a flow**.
 *
 * A widget has no ViewModel, but it does have a composition, and that composition outlives the
 * render that started it — which is the whole reason this is a `Flow` rather than the single
 * `repository.tasks.first()` snapshot it used to be. Building a `CadenceUiState` by hand is what
 * lets a widget ask the app "what does Today show?" rather than working it out again and slowly
 * disagreeing.
 *
 * Only `tasks` and `settings` are filled, because only those two are read by the views the
 * widgets name: `TaskView.Today` and `TaskView.Inbox` derive from the task list, the
 * `showCompleted` flag and the sort mode, and nothing else. A widget scoped to a project would
 * be reaching for `TaskView.Project`, which bands by section — that one has to fill `projects`
 * and `sections` here first, and would be wrong rather than merely incomplete without them.
 */
internal fun AppContainer.widgetUiStateFlow(): Flow<CadenceUiState> =
    combine(repository.tasks, settingsStore.state) { tasks, settings ->
        CadenceUiState(tasks = tasks, settings = settings)
    }

/**
 * The state a widget draws, subscribed to from **inside** `provideContent`.
 *
 * **Reading the tasks before `provideContent` renders a widget exactly once, and this cost a
 * whole evening to see.** `GlanceAppWidget.provideGlance` runs when a session starts;
 * `updateAll` — what [WidgetUpdater] calls on every `repository.tasks` emission — does *not* run
 * it again, it recomposes the content the running session already has. A `val tasks =
 * repository.tasks.first()` captured above `provideContent` is therefore a constant for the
 * lifetime of that session: every later recomposition rebuilds the identical RemoteViews, and a
 * task ticked off from the widget writes to the database, redraws, and changes nothing on screen.
 * Collecting inside the composition instead makes the redraw carry new rows.
 *
 * It also fixes the other half of the same mistake: `first()` *suspends*, so a cold database put
 * a widget on the home screen with no content at all until it answered. A composition that starts
 * with `null` draws its frame immediately and fills in a moment later.
 *
 * `null` means "not read yet", which is deliberately not the same as "no tasks" — a widget that
 * flashed its empty state on every cold start would be telling the user something untrue.
 */
@Composable
internal fun widgetUiState(container: AppContainer?): CadenceUiState? {
    val flow = remember(container) { container?.widgetUiStateFlow() ?: emptyFlow() }
    return flow.collectAsState(initial = null).value
}
