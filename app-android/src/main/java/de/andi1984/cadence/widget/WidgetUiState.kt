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
import kotlinx.coroutines.flow.first

/**
 * Enough of a [CadenceUiState] for a widget to call [taskList] on, **as a flow**.
 *
 * Building a `CadenceUiState` by hand is what lets a widget ask the app "what does Today show?"
 * rather than working it out again and slowly disagreeing.
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
 * The state a widget draws its **first** frame from, read once in `provideGlance` before
 * `provideContent` — so the first RemoteViews a session publishes already carry the rows.
 *
 * **A widget's first frame has to be the real one, because it may be the only one.** Glance runs
 * a widget's composition inside a *session* — a WorkManager job — that stays alive for about 45
 * seconds after its first frame and is then closed, composition and all. Every later update
 * starts a fresh session, which runs `provideGlance` again. That is most updates: the app writes
 * a task minutes or hours apart, the system's `updatePeriodMillis` tick arrives with the process
 * long dead, a reboot or an APK install hands the launcher nothing but `initialLayout` until the
 * provider publishes. A composition that began with `null` — "not read yet" — published a
 * header-only tile, or a blank one for the next-task widget, and only then the rows a second
 * frame later. On a phone with the process cold that second frame rode on WorkManager starting
 * the job, the database opening, the flow's first emission and the recomposer's next tick; when
 * any of those was slow, or the process was taken before it, the launcher was left holding the
 * empty frame, which is exactly "the widget shows nothing unless the app is running". Reading the
 * snapshot here costs one query on a cold database, and makes frame one correct.
 *
 * `null` only when the widget has no container to read from, which the `as?` in each
 * `provideGlance` treats as "render the empty state" rather than as a crash.
 */
internal suspend fun AppContainer?.widgetSnapshot(): CadenceUiState? =
    this?.widgetUiStateFlow()?.first()

/**
 * The state a widget draws, collected from **inside** `provideContent`, starting from the
 * [initial] snapshot `provideGlance` already read.
 *
 * Both halves are needed, and each was once the whole fix for a bug the other causes. Reading
 * only above `provideContent` froze the widget for the session's lifetime: `updateAll` within
 * those 45 seconds recomposes what the running session has rather than running `provideGlance`
 * again, so a task ticked off from the widget wrote to the database, redrew, and changed nothing
 * on screen. Reading only inside, with `null` as the first value, published the empty frame
 * described at [widgetSnapshot]. So the session starts from a real snapshot *and* keeps
 * collecting: a write that lands while the session is open recomposes the rows in place, and one
 * that lands after it is closed starts a new session whose first frame is already right.
 */
@Composable
internal fun widgetUiState(container: AppContainer?, initial: CadenceUiState?): CadenceUiState? {
    val flow = remember(container) { container?.widgetUiStateFlow() ?: emptyFlow() }
    return flow.collectAsState(initial = initial).value
}
