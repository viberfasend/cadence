package de.andi1984.cadence.domain.model

/**
 * Importance comes first in every ordering; the due date only breaks ties.
 *
 * Priority is never communicated by colour alone — every surface pairs the segmented
 * spine with the "P1"… text label.
 */
enum class Priority(val level: Int, val shortLabel: String, val title: String) {
    P1(1, "P1", "Critical"),
    P2(2, "P2", "High"),
    P3(3, "P3", "Normal"),
    P4(4, "P4", "Low");

    /** Filled segments in the priority spine. P4 renders a single dash instead. */
    val filledBars: Int
        get() = when (this) {
            P1 -> 3
            P2 -> 2
            P3 -> 1
            P4 -> 0
        }

    val label: String get() = "$shortLabel · $title"

    val explanation: String
        get() = when (this) {
            P1 -> "P1 · Critical — sorts above everything else, regardless of date."
            P2 -> "P2 · High — above normal work, still ordered by date within the level."
            P3 -> "P3 · Normal — the default. Ordered by date."
            P4 -> "P4 · Low — kept out of the way until nothing else is due."
        }

    companion object {
        val DEFAULT = P3

        fun fromLevel(level: Int): Priority = entries.firstOrNull { it.level == level } ?: DEFAULT
    }
}
