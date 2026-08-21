package de.andi1984.cadence.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-rolled navigation `androidx.navigation.compose` would have done on Android.
 *
 * Plain state and plain Kotlin, so it is testable — which is the point: the interesting behaviour
 * is not "push a route" but what happens when the window is resized across the two-pane threshold
 * with a detail open, and that is exactly the case nobody re-checks by hand.
 */
class DesktopNavigatorTest {

    private fun navigator(twoPane: Boolean = false) = DesktopNavigator().apply {
        onWidthChanged(twoPane)
    }

    @Test
    fun `a top-level destination replaces the stack rather than growing it`() {
        val navigator = navigator()

        navigator.go(Route.TaskDetail("t1"))
        navigator.switchTo(Route.Inbox)

        assertEquals(Route.Inbox, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `back and forward walk the same path in both directions`() {
        val navigator = navigator()

        navigator.go(Route.Search)
        navigator.go(Route.TaskDetail("t1"))
        navigator.back()

        assertEquals(Route.Search, navigator.current)
        assertTrue(navigator.canGoForward)

        navigator.forward()
        assertEquals(Route.TaskDetail("t1"), navigator.current)
    }

    @Test
    fun `going somewhere new drops the forward stack`() {
        val navigator = navigator()
        navigator.go(Route.Search)
        navigator.back()

        navigator.go(Route.Settings)

        assertFalse(navigator.canGoForward)
    }

    @Test
    fun `in two-pane mode a detail route fills the pane instead of the window`() {
        val navigator = navigator(twoPane = true)

        navigator.go(Route.TaskDetail("t1"))

        assertEquals(Route.Today, navigator.current)
        assertEquals(Route.TaskDetail("t1"), navigator.detail)
        // Esc still means "close what I opened".
        navigator.back()
        assertNull(navigator.detail)
        assertEquals(Route.Today, navigator.current)
    }

    @Test
    fun `narrowing the window keeps an open detail on screen`() {
        val navigator = navigator(twoPane = true)
        navigator.go(Route.TaskDetail("t1"))

        navigator.onWidthChanged(false)

        // It was on screen a moment ago; dropping it would be the window eating the user's work.
        assertNull(navigator.detail)
        assertEquals(Route.TaskDetail("t1"), navigator.current)
    }

    @Test
    fun `widening the window moves a detail destination into the pane`() {
        val navigator = navigator()
        navigator.go(Route.TaskDetail("t1"))

        navigator.onWidthChanged(true)

        assertEquals(Route.Today, navigator.current)
        assertEquals(Route.TaskDetail("t1"), navigator.detail)
    }

    @Test
    fun `a non-detail route stays a destination at any width`() {
        val navigator = navigator()
        navigator.go(Route.Settings)

        navigator.onWidthChanged(true)

        assertEquals(Route.Settings, navigator.current)
        assertNull(navigator.detail)
    }

    @Test
    fun `back at the root does nothing`() {
        val navigator = navigator()

        navigator.back()

        assertEquals(Route.Today, navigator.current)
    }

    @Test
    fun `switching away closes the detail pane`() {
        val navigator = navigator(twoPane = true)
        navigator.go(Route.ProjectDetail("p1"))

        navigator.switchTo(Route.Upcoming)

        assertNull(navigator.detail)
        assertEquals(Route.Upcoming, navigator.current)
    }
}
