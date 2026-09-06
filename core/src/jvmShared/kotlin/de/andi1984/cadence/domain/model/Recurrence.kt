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
) {
    companion object {
        /**
         * A rule from the enum *names* a published shape carries — `BackupCodec`'s file and
         * `RemoteRecords`' wire both spell `mode`, `unit`, `monthlyMode` and the weekdays as
         * `RecurrenceMode.name`/`DayOfWeek.name` strings, and both used to decode them with the
         * same four `entries.firstOrNull { it.name == x } ?: default` lines. The two DTO types stay
         * separate on purpose (ADR 0002, decision 5); only this mapping is shared.
         *
         * Lenient by design: a name nothing answers to falls back to the field's default, an
         * unknown weekday is dropped, and the interval is clamped to at least 1 — a rule that
         * reads a little wrong beats a task that fails to import.
         */
        fun fromNames(
            mode: String?,
            interval: Int,
            unit: String?,
            daysOfWeek: List<String>,
            monthlyMode: String?,
            dayOfMonth: Int?,
            nthWeek: Int?,
            nthDayOfWeek: String?,
        ) = RecurrenceRule(
            mode = RecurrenceMode.entries.firstOrNull { it.name == mode } ?: RecurrenceMode.SCHEDULE,
            interval = interval.coerceAtLeast(1),
            unit = RecurrenceUnit.entries.firstOrNull { it.name == unit } ?: RecurrenceUnit.WEEK,
            daysOfWeek = daysOfWeek.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                .toSet(),
            monthlyMode = MonthlyMode.entries.firstOrNull { it.name == monthlyMode }
                ?: MonthlyMode.DAY_OF_MONTH,
            dayOfMonth = dayOfMonth,
            nthWeek = nthWeek,
            nthDayOfWeek = nthDayOfWeek?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } },
        )
    }
}
