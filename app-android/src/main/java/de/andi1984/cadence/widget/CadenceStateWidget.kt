package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.ui.CadenceUiState
import java.time.Duration
import java.time.LocalDate

/**
 * The one `provideGlance` every task-backed widget shares, so the rules CLAUDE.md states for a
 * Glance session are enforced here once rather than re-typed — and re-risked — in every widget.
 *
 * [TaskListWidget] and [CadenceNextTaskWidget] used to each write this method out by hand, in the
 * same order, with the same comments explaining why. That duplication is exactly how a widget
 * could quietly drop one of the rules; sealing `provideGlance` `final` makes doing so a compile
 * error instead of a review miss.
 *
 * **Frame one is drawn from a snapshot read before [provideContent], and every later frame from
 * the flow collected inside it — both, never one.** [widgetSnapshot] is read here, above
 * `provideContent`, so the first RemoteViews a cold session publishes already carry real rows
 * rather than the header-only or blank frame a `null`-first collector would draw. [widgetUiState]
 * is then collected *inside* `provideContent`, so a write landing while the session stays open
 * (about 45 seconds) still recomposes it — an `updateAll` inside that window recomposes what the
 * session already has rather than running `provideGlance` again, so reading only the snapshot
 * would freeze the widget for the rest of the session.
 *
 * Midnight and staleness are the same two calls both widgets used to place beside that pair:
 * [WidgetMidnightRefresh.schedule] re-arms the day-turnover alarm from every session start, and
 * `syncInBackgroundIfStale` is the "a widget being drawn is a look at the home screen" round (ADR
 * 0002, amendment 1).
 */
abstract class CadenceStateWidget : GlanceAppWidget() {

    final override suspend fun provideGlance(context: Context, id: GlanceId) {
        // `as?` rather than a hard cast: a widget that throws is reported by the launcher as a
        // crashed widget, so an impossible-in-practice failure degrades to the empty state —
        // still a working quick-add button for the widgets that draw one — instead of a broken
        // tile.
        val container = (context.applicationContext as? CadenceApplication)?.container

        val initial = container.widgetSnapshot()

        WidgetMidnightRefresh.schedule(context)

        container?.syncEngine?.syncInBackgroundIfStale(WIDGET_STALENESS)

        provideContent {
            val state = widgetUiState(container, initial)
            val today = LocalDate.now()
            GlanceTheme(colors = CadenceWidgetColors) {
                Content(context, state, today)
            }
        }
    }

    /**
     * What this widget draws from [state] — `null` only when there was no container to read from
     * — and [today]. The one thing left for a subclass to say; everything about *how* it got
     * `state` lives in [provideGlance] above.
     */
    @Composable
    protected abstract fun Content(context: Context, state: CadenceUiState?, today: LocalDate)
}

/** How old the last round may be before a widget redraw asks the server again. */
internal val WIDGET_STALENESS: Duration = Duration.ofMinutes(5)
