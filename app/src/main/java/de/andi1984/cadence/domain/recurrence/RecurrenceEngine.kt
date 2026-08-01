package de.andi1984.cadence.domain.recurrence

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

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
     * With [RecurrenceRule.keepMissed] off, occurrences that are already in the past are
     * skipped so the task reappears in the future rather than immediately overdue.
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
        if (!rule.keepMissed) {
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

    /** Human-readable summary, e.g. "Every 2 weeks on Thu" or "3 days after done". */
    fun describe(rule: RecurrenceRule): String {
        val interval = rule.interval.coerceAtLeast(1)
        if (rule.mode == RecurrenceMode.AFTER_COMPLETION) {
            return "$interval ${unitWord(rule.unit, interval)} after done"
        }
        return when (rule.unit) {
            RecurrenceUnit.DAY ->
                if (interval == 1) "Daily" else "Every $interval days"

            RecurrenceUnit.WEEK -> {
                val prefix = if (interval == 1) "Weekly" else "Every $interval weeks"
                if (rule.daysOfWeek.isEmpty()) prefix else "$prefix on ${dayList(rule.daysOfWeek)}"
            }

            RecurrenceUnit.MONTH -> {
                val prefix = if (interval == 1) "Monthly" else "Every $interval months"
                "$prefix on ${monthlyPhrase(rule)}"
            }

            RecurrenceUnit.YEAR ->
                if (interval == 1) "Yearly" else "Every $interval years"
        }
    }

    private fun monthlyPhrase(rule: RecurrenceRule): String = when (rule.monthlyMode) {
        MonthlyMode.DAY_OF_MONTH -> "the ${ordinal(rule.dayOfMonth ?: 1)}"
        MonthlyMode.LAST_DAY -> "the last day"
        MonthlyMode.LAST_WEEKDAY -> "the last weekday"
        MonthlyMode.NTH_WEEKDAY -> {
            val dow = (rule.nthDayOfWeek ?: DayOfWeek.MONDAY)
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val nth = rule.nthWeek ?: 1
            if (nth >= 5) "the last $dow" else "the ${ordinal(nth)} $dow"
        }
    }

    private fun dayList(days: Set<DayOfWeek>): String = days
        .sortedBy { it.value }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

    private fun unitWord(unit: RecurrenceUnit, count: Int): String {
        val singular = when (unit) {
            RecurrenceUnit.DAY -> "day"
            RecurrenceUnit.WEEK -> "week"
            RecurrenceUnit.MONTH -> "month"
            RecurrenceUnit.YEAR -> "year"
        }
        return if (count == 1) singular else "${singular}s"
    }

    fun ordinal(value: Int): String {
        val suffix = when {
            value % 100 in 11..13 -> "th"
            value % 10 == 1 -> "st"
            value % 10 == 2 -> "nd"
            value % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$value$suffix"
    }
}
