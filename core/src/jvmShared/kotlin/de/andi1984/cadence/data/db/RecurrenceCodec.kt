package de.andi1984.cadence.data.db

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import java.time.DayOfWeek

/**
 * Recurrence rules live in a single TEXT column as `key=value` pairs so the schema does not
 * grow a column per rule field. Unknown or malformed input decodes to `null` (no recurrence)
 * rather than throwing.
 */
object RecurrenceCodec {

    private const val VERSION = "v1"

    fun encode(rule: RecurrenceRule?): String? {
        if (rule == null) return null
        val parts = mutableListOf(
            VERSION,
            "mode=${rule.mode.name}",
            "interval=${rule.interval}",
            "unit=${rule.unit.name}",
            "monthly=${rule.monthlyMode.name}",
            "keepMissed=${rule.keepMissed}",
        )
        if (rule.daysOfWeek.isNotEmpty()) {
            parts += "dows=" + rule.daysOfWeek.sortedBy { it.value }.joinToString(",") { it.name }
        }
        rule.dayOfMonth?.let { parts += "dom=$it" }
        rule.nthWeek?.let { parts += "nthWeek=$it" }
        rule.nthDayOfWeek?.let { parts += "nthDow=${it.name}" }
        return parts.joinToString(";")
    }

    fun decode(raw: String?): RecurrenceRule? {
        if (raw.isNullOrBlank()) return null
        val segments = raw.split(";")
        if (segments.firstOrNull() != VERSION) return null
        val values = segments.drop(1)
            .mapNotNull { segment ->
                val index = segment.indexOf('=')
                if (index <= 0) null else segment.take(index) to segment.substring(index + 1)
            }
            .toMap()

        val mode = values["mode"]?.let { name ->
            RecurrenceMode.entries.firstOrNull { it.name == name }
        } ?: return null
        val unit = values["unit"]?.let { name ->
            RecurrenceUnit.entries.firstOrNull { it.name == name }
        } ?: RecurrenceUnit.WEEK

        return RecurrenceRule(
            mode = mode,
            interval = values["interval"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            unit = unit,
            daysOfWeek = values["dows"]
                ?.split(",")
                ?.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                ?.toSet()
                .orEmpty(),
            monthlyMode = values["monthly"]?.let { name ->
                MonthlyMode.entries.firstOrNull { it.name == name }
            } ?: MonthlyMode.DAY_OF_MONTH,
            dayOfMonth = values["dom"]?.toIntOrNull(),
            nthWeek = values["nthWeek"]?.toIntOrNull(),
            nthDayOfWeek = values["nthDow"]?.let { name ->
                DayOfWeek.entries.firstOrNull { it.name == name }
            },
            // A row written before this segment existed means "the default", not "keep them":
            // defaulting it to true here is what left every legacy rule stepping a single
            // interval on however far overdue it had fallen.
            keepMissed = values["keepMissed"]?.toBooleanStrictOrNull() ?: false,
        )
    }
}
