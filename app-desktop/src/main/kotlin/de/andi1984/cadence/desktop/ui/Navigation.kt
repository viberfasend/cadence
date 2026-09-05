package de.andi1984.cadence.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the desktop can be.
 *
 * `:app-android` gets this from `androidx.navigation.compose`, which is an Android artifact this
 * module has no dependency on (ADR 0001 §8). A sealed interface and a pair of stacks is the whole
 * replacement, and it buys something the `NavHost` does not give for free either: a *forward*
 * stack, so `Alt`+`→` means what it means in every other desktop application.
 */
sealed interface Route {
    data object Today : Route
    data object Upcoming : Route
    data object Inbox : Route
    data object Projects : Route
    data object Search : Route
    data object Settings : Route
    data object Triage : Route
    data object Tags : Route
    /** Ask Cadence (ADR 0006). A top-level view like Search: a place to go, not a detail. */
    data object Assistant : Route
    data class TaskDetail(val taskId: String) : Route
    data class ProjectDetail(val projectId: String) : Route
    data class TagDetail(val tagId: String) : Route

    /**
     * Detail routes fill the second pane when the window is wide enough, rather than replacing
     * the list (issue #131). Everything else is a whole-window destination.
     *
     * A tag's list is a detail route for the same reason a project's is: the sidebar names it, so
     * clicking one on a wide window should fill the pane beside the sidebar rather than take the
     * whole window over.
     */
    val isDetail: Boolean
        get() = this is TaskDetail || this is ProjectDetail || this is TagDetail
}

/**
 * The back stack, a forward stack, and the detail pane's current occupant.
 *
 * Two-pane and single-pane are the same navigator seen from two widths: [go] puts a detail route
 * in [detail] when [twoPane] is set and pushes it as a destination otherwise, and [back] undoes
 * whichever of the two happened. That is what keeps `Esc` and `Alt`+`←` meaning one thing while
 * the window is being resized across the threshold.
 */
@Stable
class DesktopNavigator {

    var twoPane: Boolean by mutableStateOf(false)
        private set

    private var backStack: List<Route> by mutableStateOf(listOf(Route.Today))
    private var forwardStack: List<Route> by mutableStateOf(emptyList())

    /** What the list pane draws. */
    val current: Route get() = backStack.last()

    /** What the detail pane draws, or null when it is empty. Always null in single-pane mode. */
    var detail: Route? by mutableStateOf(null)
        private set

    val canGoBack: Boolean get() = detail != null || backStack.size > 1

    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** A top-level destination: replaces the stack, the way tapping a rail item always has. */
    fun switchTo(route: Route) {
        if (current == route && detail == null) return
        backStack = listOf(route)
        forwardStack = emptyList()
        detail = null
    }

    /** Opens [route], as a pane or as a destination depending on the width. */
    fun go(route: Route) {
        forwardStack = emptyList()
        if (twoPane && route.isDetail) {
            detail = route
        } else {
            if (current == route) return
            backStack = backStack + route
            detail = null
        }
    }

    fun back() {
        when {
            detail != null -> detail = null
            backStack.size > 1 -> {
                forwardStack = forwardStack + backStack.last()
                backStack = backStack.dropLast(1)
            }
        }
    }

    fun forward() {
        val next = forwardStack.lastOrNull() ?: return
        forwardStack = forwardStack.dropLast(1)
        if (twoPane && next.isDetail) detail = next else backStack = backStack + next
    }

    /**
     * Keeps the two panes honest as the window crosses the threshold.
     *
     * Narrowing with a detail pane open pushes it onto the stack — it was on screen a moment ago
     * and must not vanish. Widening pulls a detail route off the top of the stack into the pane,
     * so the same window state means the same thing at either width.
     */
    fun onWidthChanged(enabled: Boolean) {
        if (enabled == twoPane) return
        twoPane = enabled
        if (enabled) {
            val top = backStack.last()
            if (top.isDetail && backStack.size > 1) {
                backStack = backStack.dropLast(1)
                detail = top
            }
        } else {
            detail?.let { open ->
                detail = null
                backStack = backStack + open
            }
        }
    }
}
