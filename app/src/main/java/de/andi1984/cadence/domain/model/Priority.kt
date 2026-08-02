package de.andi1984.cadence.domain.model

/**
 * Importance comes first in every ordering; the due date only breaks ties.
 *
 * Priority is never communicated by colour alone — every surface pairs the segmented
 * spine with the "P1"… text label. [shortLabel] is the same in every language; the spoken
 * name ("Critical") and the explanation live in
 * [de.andi1984.cadence.ui.format.PriorityLabels].
 */
enum class Priority(val level: Int, val shortLabel: String) {
    P1(1, "P1"),
    P2(2, "P2"),
    P3(3, "P3"),
    P4(4, "P4");

    /** Filled segments in the priority spine. P4 renders a single dash instead. */
    val filledBars: Int
        get() = when (this) {
            P1 -> 3
            P2 -> 2
            P3 -> 1
            P4 -> 0
        }

    companion object {
        val DEFAULT = P3

        fun fromLevel(level: Int): Priority = entries.firstOrNull { it.level == level } ?: DEFAULT
    }
}
