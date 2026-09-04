package de.andi1984.cadence.domain.recurrence

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Turns a [RecurrenceRule] into concrete dates.
 *
 * `SCHEDULE` rules walk the calendar from the previous occurrence; `AFTER_COMPLETION`
 * rules are anchored to the day the task was finished.
 */
object RecurrenceEngine {

    private const val SAFETY_LIMIT = 400

    /**
     * The first occurrence strictly after [from].
     *
     * @param from for schedule rules this is the previous due date, for completion-anchored
     *   rules it is the day the task was completed.
     */
    fun nextAfter(rule: RecurrenceRule, from: LocalDate): LocalDate {
        val interval = rule.interval.coerceAtLeast(1)
        if (rule.mode == RecurrenceMode.AFTER_COMPLETION) {
            return when (rule.unit) {
                RecurrenceUnit.DAY -> from.plusDays(interval.toLong())
                RecurrenceUnit.WEEK -> from.plusWeeks(interval.toLong())
                RecurrenceUnit.MONTH -> from.plusMonths(interval.toLong())
                RecurrenceUnit.YEAR -> from.plusYears(interval.toLong())
            }
        }
        return when (rule.unit) {
            RecurrenceUnit.DAY -> from.plusDays(interval.toLong())
            RecurrenceUnit.WEEK -> nextWeekly(rule, from, interval)
            RecurrenceUnit.MONTH -> nextMonthly(rule, from, interval)
            RecurrenceUnit.YEAR -> nextYearly(rule, from, interval)
        }
    }

    /** The next [count] occurrences after [from], used by the "Next three" preview. */
    fun nextOccurrences(rule: RecurrenceRule, from: LocalDate, count: Int): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = from
        repeat(count) {
            val next = nextAfter(rule, cursor)
            dates += next
            cursor = next
        }
        return dates
    }

    /**
     * The due date a rescheduled instance should land on when the previous one is completed.
     *
     * A task completed on its day, or early, steps on from its own due date, which is already
     * today or later. An *overdue* one hands over to the first occurrence that is not behind
     * us — today's, when the rule names today, otherwise the next one in the future — however
     * many it missed in between: ticking off a daily task last due in March must not hand back
     * one due in March, and a Monday task done a week late on a Monday is due today, not next
     * week. The occurrence being completed is never handed back, since it is strictly before
     * the completion day in that branch and strictly the anchor in the other.
     */
    fun dueDateAfterCompletion(
        rule: RecurrenceRule,
        previousDue: LocalDate?,
        completedOn: LocalDate,
    ): LocalDate {
        if (rule.mode == RecurrenceMode.AFTER_COMPLETION) {
            return nextAfter(rule, completedOn)
        }
        val anchor = previousDue ?: completedOn
        var next = nextAfter(rule, anchor)
        if (anchor.isBefore(completedOn)) {
            var guard = 0
            while (next.isBefore(completedOn) && guard < SAFETY_LIMIT) {
                next = nextAfter(rule, next)
                guard++
            }
        }
        return next
    }

    private fun nextWeekly(rule: RecurrenceRule, from: LocalDate, interval: Int): LocalDate {
        if (rule.daysOfWeek.isEmpty()) return from.plusWeeks(interval.toLong())
        val weekStart = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val selected = rule.daysOfWeek.sortedBy { it.value }
        val thisWeek = selected
            .map { weekStart.plusDays((it.value - 1).toLong()) }
            .firstOrNull { it.isAfter(from) }
        if (thisWeek != null) return thisWeek
        val nextWeekStart = weekStart.plusWeeks(interval.toLong())
        return nextWeekStart.plusDays((selected.first().value - 1).toLong())
    }

    private fun nextMonthly(rule: RecurrenceRule, from: LocalDate, interval: Int): LocalDate {
        var month = YearMonth.from(from)
        var guard = 0
        while (guard < SAFETY_LIMIT) {
            val candidate = dateInMonth(rule, month)
            if (candidate.isAfter(from)) return candidate
            month = month.plusMonths(interval.toLong())
            guard++
        }
        return from.plusMonths(interval.toLong())
    }

    private fun nextYearly(rule: RecurrenceRule, from: LocalDate, interval: Int): LocalDate {
        if (rule.monthlyMode == MonthlyMode.DAY_OF_MONTH && rule.dayOfMonth == null) {
            return from.plusYears(interval.toLong())
        }
        var month = YearMonth.from(from)
        var guard = 0
        while (guard < SAFETY_LIMIT) {
            val candidate = dateInMonth(rule, month)
            if (candidate.isAfter(from)) return candidate
            month = month.plusYears(interval.toLong())
            guard++
        }
        return from.plusYears(interval.toLong())
    }

    /** Resolves the rule's monthly mode inside a concrete month. */
    fun dateInMonth(rule: RecurrenceRule, month: YearMonth): LocalDate = when (rule.monthlyMode) {
        MonthlyMode.DAY_OF_MONTH -> {
            val day = (rule.dayOfMonth ?: 1).coerceIn(1, month.lengthOfMonth())
            month.atDay(day)
        }

        MonthlyMode.LAST_DAY -> month.atEndOfMonth()

        MonthlyMode.LAST_WEEKDAY -> {
            var day = month.atEndOfMonth()
            while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
                day = day.minusDays(1)
            }
            day
        }

        MonthlyMode.NTH_WEEKDAY -> {
            val dow = rule.nthDayOfWeek ?: DayOfWeek.MONDAY
            val nth = (rule.nthWeek ?: 1).coerceIn(1, 5)
            if (nth >= 5) {
                month.atDay(1).with(TemporalAdjusters.lastInMonth(dow))
            } else {
                month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(nth, dow))
            }
        }
    }

    /**
     * Breaks a rule down into the pieces a sentence needs — "every 2 weeks", "on Thu" — without
     * committing to any wording. [de.andi1984.cadence.ui.format.describeRecurrence] turns the
     * result into localised text.
     */
    fun summarize(rule: RecurrenceRule): RecurrenceSummary {
        val interval = rule.interval.coerceAtLeast(1)
        if (rule.mode == RecurrenceMode.AFTER_COMPLETION) {
            return RecurrenceSummary.AfterCompletion(interval = interval, unit = rule.unit)
        }
        return RecurrenceSummary.Schedule(
            interval = interval,
            unit = rule.unit,
            daysOfWeek = if (rule.unit == RecurrenceUnit.WEEK) {
                rule.daysOfWeek.sortedBy { it.value }
            } else {
                emptyList()
            },
            monthly = if (rule.unit == RecurrenceUnit.MONTH) monthlyPhrase(rule) else null,
        )
    }

    private fun monthlyPhrase(rule: RecurrenceRule): MonthlyPhrase = when (rule.monthlyMode) {
        MonthlyMode.DAY_OF_MONTH -> MonthlyPhrase.DayOfMonth(rule.dayOfMonth ?: 1)
        MonthlyMode.LAST_DAY -> MonthlyPhrase.LastDay
        MonthlyMode.LAST_WEEKDAY -> MonthlyPhrase.LastWeekday
        MonthlyMode.NTH_WEEKDAY -> MonthlyPhrase.NthWeekday(
            nth = rule.nthWeek ?: 1,
            day = rule.nthDayOfWeek ?: DayOfWeek.MONDAY,
        )
    }
}
