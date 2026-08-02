package de.andi1984.cadence.domain.recurrence

import de.andi1984.cadence.domain.model.RecurrenceUnit
import java.time.DayOfWeek

/**
 * A language-neutral description of a rule.
 *
 * The engine decides *what* a rule says, the UI layer decides *how* to say it — so the summary
 * stays unit-testable without Android resources and the wording can be translated.
 */
sealed interface RecurrenceSummary {

    /** "3 days after done". */
    data class AfterCompletion(
        val interval: Int,
        val unit: RecurrenceUnit,
    ) : RecurrenceSummary

    /**
     * "Every 2 weeks on Thu" — [daysOfWeek] applies to weekly rules, [monthly] to monthly ones,
     * and both are empty/null for the rules that need no qualifier.
     */
    data class Schedule(
        val interval: Int,
        val unit: RecurrenceUnit,
        val daysOfWeek: List<DayOfWeek> = emptyList(),
        val monthly: MonthlyPhrase? = null,
    ) : RecurrenceSummary
}

/** How a monthly rule picks its day, resolved down to the values the wording needs. */
sealed interface MonthlyPhrase {

    data class DayOfMonth(val day: Int) : MonthlyPhrase

    data object LastDay : MonthlyPhrase

    data object LastWeekday : MonthlyPhrase

    /** An [nth] of 5 or more means "the last". */
    data class NthWeekday(val nth: Int, val day: DayOfWeek) : MonthlyPhrase
}
