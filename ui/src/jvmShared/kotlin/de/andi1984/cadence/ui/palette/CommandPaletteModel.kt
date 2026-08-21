package de.andi1984.cadence.ui.palette

/**
 * The command palette's brain: a scored fuzzy match over everything the app can go to or do.
 *
 * Pure Kotlin, no Compose, so the ranking is testable — which matters more here than in most
 * places, because a palette that puts the wrong row first is worse than no palette: it turns a
 * two-keystroke action into "type, look, arrow down, look again".
 */
enum class PaletteKind { Command, Project, Task }

/**
 * One thing the palette can offer.
 *
 * [id] is opaque to this file; the shell hands it back when the row is chosen and decides what it
 * meant. [subtitle] is matched against too — a task's project is often how someone remembers it.
 */
data class PaletteEntry(
    val id: String,
    val title: String,
    val kind: PaletteKind,
    val subtitle: String? = null,
)

/** An entry that matched, with the score it matched at — exposed so tests can assert order. */
data class PaletteMatch(val entry: PaletteEntry, val score: Int)

object CommandPalette {

    /** Typing this first restricts the results to one kind. */
    const val COMMAND_PREFIX = '>'
    const val PROJECT_PREFIX = '#'

    /** How many of each kind survive, so one long task list cannot crowd out every command. */
    const val DEFAULT_LIMIT_PER_KIND = 6

    /**
     * [entries] filtered and ranked for [query], grouped by kind in [PaletteKind]'s own order.
     *
     * An empty query is not "no matches" but "nothing typed yet": every kind's first few entries
     * come back in the order they were given, which is how the palette opens on something useful.
     */
    fun search(
        query: String,
        entries: List<PaletteEntry>,
        limitPerKind: Int = DEFAULT_LIMIT_PER_KIND,
    ): List<PaletteMatch> {
        val restrictedTo = when (query.firstOrNull()) {
            COMMAND_PREFIX -> PaletteKind.Command
            PROJECT_PREFIX -> PaletteKind.Project
            else -> null
        }
        // The prefix is an instruction, not part of what is being searched for.
        val needle = if (restrictedTo == null) query.trim() else query.drop(1).trim()
        val candidates = entries.filter { restrictedTo == null || it.kind == restrictedTo }

        if (needle.isEmpty()) {
            return candidates
                .groupBy { it.kind }
                .toSortedMap(compareBy { it.ordinal })
                .flatMap { (_, group) -> group.take(limitPerKind).map { PaletteMatch(it, 0) } }
        }

        return candidates
            .mapNotNull { entry ->
                val titleScore = score(needle, entry.title)
                // A subtitle match counts, but never as much as the name itself.
                val subtitleScore = entry.subtitle?.let { score(needle, it)?.minus(6) }
                val best = listOfNotNull(titleScore, subtitleScore).maxOrNull() ?: return@mapNotNull null
                PaletteMatch(entry, best)
            }
            .groupBy { it.entry.kind }
            .toSortedMap(compareBy { it.ordinal })
            .flatMap { (_, group) ->
                group.sortedWith(compareByDescending<PaletteMatch> { it.score }.thenBy { it.entry.title })
                    .take(limitPerKind)
            }
    }

    /**
     * How well [text] answers [query], or null when it does not answer at all.
     *
     * A subsequence match, scored by *where* the letters landed rather than merely that they did:
     * the first character of the text, and the first character of any word in it, are what people
     * actually type. Without the word-boundary bonus, "pr" ranks "Su**pr**ise" above "**P**ay
     * **r**ent", which is the wrong answer to a question nobody meant to ask.
     */
    internal fun score(query: String, text: String): Int? {
        val needle = query.lowercase()
        val haystack = text.lowercase()
        var total = 0
        var searchFrom = 0
        var previousMatch = -2

        needle.forEach { char ->
            if (char.isWhitespace()) return@forEach
            val at = haystack.indexOf(char, searchFrom)
            if (at < 0) return null
            total += 1
            if (at == 0) total += 8
            if (at > 0 && isBoundary(haystack[at - 1])) total += 4
            if (at == previousMatch + 1) total += 3
            // A letter found far past the last one is a weaker match than one found right after.
            total -= ((at - searchFrom).coerceAtMost(4))
            previousMatch = at
            searchFrom = at + 1
        }
        return total
    }

    private fun isBoundary(char: Char): Boolean = !char.isLetterOrDigit()
}
