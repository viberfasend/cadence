package de.andi1984.cadence.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which row the keyboard is on.
 *
 * The order is read off where the rows *are*, not off any list, because nothing in `:ui` has one
 * list — seven screens draw rows in bands, under headers, with checklists spliced in. These pin
 * the two things that follow from that: the order is vertical, and a row that leaves the screen
 * takes the selection with it rather than leaving `x` pointed at something invisible.
 */
class RowSelectionStateTest {

    private fun state(vararg rows: Pair<String, Float>) = RowSelectionState().apply {
        rows.forEach { (id, top) -> register(id, top) }
    }

    @Test
    fun `the order is the order the rows are drawn in, not the order they registered`() {
        // A `LazyColumn` composes in whatever order it likes, and a band drawn later can sit
        // higher up the screen.
        val selection = state("bottom" to 300f, "top" to 10f, "middle" to 150f)

        assertEquals(listOf("top", "middle", "bottom"), selection.visibleIds())
    }

    @Test
    fun `moving down from nothing selects the first row, and up selects the last`() {
        val selection = state("a" to 0f, "b" to 50f)

        selection.moveBy(1)
        assertEquals("a", selection.selectedId)

        selection.select(null)
        selection.moveBy(-1)
        assertEquals("b", selection.selectedId)
    }

    @Test
    fun `the selection stops at the ends rather than wrapping`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("b")

        selection.moveBy(1)
        assertEquals("b", selection.selectedId)

        selection.select("a")
        selection.moveBy(-1)
        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `a row that leaves the screen takes the selection with it`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("b")

        selection.unregister("b")

        // Otherwise the next `x` completes a task nobody can see.
        assertNull(selection.selectedId)
        assertEquals(listOf("a"), selection.visibleIds())
    }

    @Test
    fun `a row leaving while another is selected changes nothing`() {
        val selection = state("a" to 0f, "b" to 50f)
        selection.select("a")

        selection.unregister("b")

        assertEquals("a", selection.selectedId)
    }

    @Test
    fun `moving in an empty list does nothing`() {
        val selection = RowSelectionState()

        selection.moveBy(1)

        assertNull(selection.selectedId)
    }
}
