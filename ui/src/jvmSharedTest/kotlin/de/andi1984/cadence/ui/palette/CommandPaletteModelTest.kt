package de.andi1984.cadence.ui.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's ranking.
 *
 * Worth its own tests because a palette that puts the wrong row first is worse than none: it
 * turns a two-keystroke action into "type, look, arrow down, look again". Every case here is one
 * that came out wrong from a plainer scoring rule.
 */
class CommandPaletteModelTest {

    private fun command(title: String) = PaletteEntry(title, title, PaletteKind.Command)
    private fun project(title: String) = PaletteEntry(title, title, PaletteKind.Project)
    private fun task(title: String, subtitle: String? = null) =
        PaletteEntry(title, title, PaletteKind.Task, subtitle)

    private fun titles(matches: List<PaletteMatch>) = matches.map { it.entry.title }

    @Test
    fun `a word-boundary hit beats one buried mid-word`() {
        val entries = listOf(task("Suprise party"), task("Pay rent"))

        val results = CommandPalette.search("pr", entries)

        // "**P**ay **r**ent" is what someone typing "pr" means; "Su**pr**ise" merely contains it.
        assertEquals(listOf("Pay rent", "Suprise party"), titles(results))
    }

    @Test
    fun `an exact prefix beats a scattered subsequence`() {
        val entries = listOf(task("Take out the recycling"), task("Tax return"))

        val results = CommandPalette.search("tax", entries)

        assertEquals("Tax return", titles(results).first())
    }

    @Test
    fun `a match on the subtitle counts, but never above a match on the name`() {
        val entries = listOf(
            task("Buy stamps", subtitle = "Errands"),
            task("Errand list"),
        )

        val results = CommandPalette.search("errand", entries)

        assertEquals(listOf("Errand list", "Buy stamps"), titles(results))
    }

    @Test
    fun `a leading angle bracket restricts to commands and is not itself searched for`() {
        val entries = listOf(command("Sync now"), project("Syncing thoughts"), task("Sync the phone"))

        val results = CommandPalette.search(">sync", entries)

        assertEquals(listOf("Sync now"), titles(results))
    }

    @Test
    fun `a leading hash restricts to projects`() {
        val entries = listOf(command("Home screen"), project("Home"), task("Homework"))

        assertEquals(listOf("Home"), titles(CommandPalette.search("#home", entries)))
    }

    @Test
    fun `results are grouped by kind, commands first`() {
        val entries = listOf(task("Ship it"), project("Shipping"), command("Show shortcuts"))

        val kinds = CommandPalette.search("sh", entries).map { it.entry.kind }

        assertEquals(listOf(PaletteKind.Command, PaletteKind.Project, PaletteKind.Task), kinds)
    }

    @Test
    fun `the cap keeps the best of a kind, not the first few of it`() {
        val entries = (1..20).map { task("Task $it") } + task("Ta")

        val results = CommandPalette.search("ta", entries, limitPerKind = 3)

        assertEquals(3, results.size)
        // "Ta" is a two-character exact hit; the numbered ones all match equally further in.
        assertTrue("Ta" in titles(results))
    }

    @Test
    fun `nothing matching comes back empty rather than everything`() {
        val entries = listOf(task("Water the plants"), project("Home"))

        assertTrue(CommandPalette.search("zzz", entries).isEmpty())
    }

    @Test
    fun `an empty query is not an empty result — it is the palette just opened`() {
        val entries = listOf(command("Sync now"), project("Home"), task("Water the plants"))

        assertEquals(3, CommandPalette.search("", entries).size)
        // And a bare prefix restricts without filtering everything out.
        assertEquals(listOf("Home"), titles(CommandPalette.search("#", entries)))
    }

    @Test
    fun `matching ignores case in both directions`() {
        val entries = listOf(task("Water the Plants"))

        assertTrue(CommandPalette.search("WATER", entries).isNotEmpty())
        assertTrue(CommandPalette.search("plants", entries).isNotEmpty())
    }
}
