package de.andi1984.cadence.domain.model

import java.time.DayOfWeek

/** Whether the rule runs on a calendar schedule or is anchored to the day you finish. */
enum class RecurrenceMode { SCHEDULE, AFTER_COMPLETION }

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/** How a monthly (or yearly) rule picks its day. */
enum class MonthlyMode { DAY_OF_MONTH, LAST_DAY, LAST_WEEKDAY, NTH_WEEKDAY }

/**
 * A recurrence rule. Both directions of the design are supported: calendar rules
 * ("every 3 months on the last weekday") and completion-anchored ones
 * ("3 days after done").
 */
data class RecurrenceRule(
    val mode: RecurrenceMode = RecurrenceMode.SCHEDULE,
    val interval: Int = 1,
    val unit: RecurrenceUnit = RecurrenceUnit.WEEK,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val dayOfMonth: Int? = null,
    val nthWeek: Int? = null,
    val nthDayOfWeek: DayOfWeek? = null,
    /** Skipped instances stay overdue instead of vanishing. */
    val keepMissed: Boolean = true,
)
