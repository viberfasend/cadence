package de.andi1984.cadence.domain.parse

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class TokenKind { DATE, TIME, RECURRENCE, PRIORITY, PROJECT }

data class TokenSpan(val range: IntRange, val kind: TokenKind)

data class ParsedQuickAdd(
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority? = null,
    val projectId: Long? = null,
    val projectName: String? = null,
    val recurrence: RecurrenceRule? = null,
    val spans: List<TokenSpan> = emptyList(),
)

/**
 * Parses the quick-add line: everything is optional, plain words stay in the title.
 *
 * Recognised, in this order (so "every 1st" is a rule rather than a date):
 *  - `!p2` / `!2` — priority
 *  - `#Home` / `#Q3Launch` — project, matched against existing names
 *  - `every 2 weeks on thu`, `every 1st`, `daily`, `3 days after done` — recurrence
 *  - `tomorrow`, `next friday`, `in 3 days`, `24.12.`, `24 Dec`, `2026-12-24` — due date
 *  - `at 17:00`, `17:00`, `9am` — due time
 */
object QuickAddParser {

    private val dayNames: Map<String, DayOfWeek> = buildMap {
        DayOfWeek.entries.forEach { day ->
            val full = day.name.lowercase()
            put(full, day)
            put(full.take(3), day)
        }
    }

    private val monthNames: Map<String, Int> = buildMap {
        val months = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        months.forEachIndexed { index, name ->
            put(name, index + 1)
            put(name.take(3), index + 1)
        }
    }

    private val dayNamePattern = dayNames.keys.sortedByDescending { it.length }.joinToString("|")
    private val monthNamePattern = monthNames.keys.sortedByDescending { it.length }.joinToString("|")

    fun parse(
        input: String,
        projects: List<Project> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): ParsedQuickAdd {
        val consumed = mutableListOf<IntRange>()
        val spans = mutableListOf<TokenSpan>()

        fun claim(range: IntRange, kind: TokenKind) {
            consumed += range
            spans += TokenSpan(range, kind)
        }

        fun free(range: IntRange): Boolean = consumed.none { existing ->
            range.first <= existing.last && existing.first <= range.last
        }

        fun firstMatch(pattern: Regex): MatchResult? =
            pattern.findAll(input).firstOrNull { free(it.range) }

        // ── Priority ───────────────────────────────────────────────────────────────
        var priority: Priority? = null
        firstMatch(Regex("""!p?([1-4])\b""", RegexOption.IGNORE_CASE))?.let { match ->
            priority = Priority.fromLevel(match.groupValues[1].toInt())
            claim(match.range, TokenKind.PRIORITY)
        }

        // ── Project ────────────────────────────────────────────────────────────────
        var projectId: Long? = null
        var projectName: String? = null
        firstMatch(Regex("""#([\p{L}\p{N}_-]+)"""))?.let { match ->
            val typed = match.groupValues[1]
            val matched = projects.firstOrNull { it.name.equals(typed, ignoreCase = true) }
                ?: projects.firstOrNull {
                    it.name.replace(" ", "").equals(typed, ignoreCase = true)
                }
                ?: projects.firstOrNull { it.name.startsWith(typed, ignoreCase = true) }
            projectId = matched?.id
            projectName = matched?.name ?: typed
            claim(match.range, TokenKind.PROJECT)
        }

        val find: (Regex) -> MatchResult? = { pattern -> firstMatch(pattern) }
        val take: (IntRange, TokenKind) -> Unit = { range, kind -> claim(range, kind) }

        // ── Recurrence ─────────────────────────────────────────────────────────────
        val recurrence = parseRecurrence(find, take)

        // ── Due date ───────────────────────────────────────────────────────────────
        val dueDate = parseDate(today, find, take)

        // ── Time ───────────────────────────────────────────────────────────────────
        val dueTime = parseTime(find, take)

        val title = buildTitle(input, consumed)
        val resolvedDue = dueDate ?: recurrence?.let { rule ->
            when (rule.mode) {
                RecurrenceMode.SCHEDULE -> RecurrenceEngine.nextAfter(rule, today.minusDays(1))
                RecurrenceMode.AFTER_COMPLETION -> today
            }
        }

        return ParsedQuickAdd(
            title = title,
            dueDate = resolvedDue,
            dueTime = dueTime,
            priority = priority,
            projectId = projectId,
            projectName = projectName,
            recurrence = recurrence,
            spans = spans.sortedBy { it.range.first },
        )
    }

    private fun parseRecurrence(
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): RecurrenceRule? {
        // "3 days after done" / "2 weeks after I finish"
        firstMatch(
            Regex(
                """\b(\d+)\s+(day|week|month|year)s?\s+after\s+""" +
                    """(done|completion|finishing|i\s+finish|it'?s\s+done)\b""",
                RegexOption.IGNORE_CASE,
            ),
        )?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            return RecurrenceRule(
                mode = RecurrenceMode.AFTER_COMPLETION,
                interval = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                unit = unitOf(match.groupValues[2]),
            )
        }

        // "every 1st", "every 15th of the month"
        firstMatch(
            Regex("""\bevery\s+(\d{1,2})(st|nd|rd|th)\b(\s+of\s+the\s+month)?""", RegexOption.IGNORE_CASE),
        )?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.MONTH,
                monthlyMode = MonthlyMode.DAY_OF_MONTH,
                dayOfMonth = match.groupValues[1].toInt().coerceIn(1, 31),
            )
        }

        // "every last weekday" / "every last day"
        firstMatch(Regex("""\bevery\s+last\s+(weekday|day)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.MONTH,
                monthlyMode = if (match.groupValues[1].equals("weekday", ignoreCase = true)) {
                    MonthlyMode.LAST_WEEKDAY
                } else {
                    MonthlyMode.LAST_DAY
                },
            )
        }

        // "every 2 weeks on thu", "every 3 months", "every other day"
        firstMatch(
            Regex(
                """\bevery\s+(other\s+|\d+\s+)?(day|week|month|year)s?""" +
                    """(\s+on\s+($dayNamePattern)s?)?\b""",
                RegexOption.IGNORE_CASE,
            ),
        )?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val rawInterval = match.groupValues[1].trim()
            val interval = when {
                rawInterval.isEmpty() -> 1
                rawInterval.equals("other", ignoreCase = true) -> 2
                else -> rawInterval.toIntOrNull()?.coerceAtLeast(1) ?: 1
            }
            val dow = match.groupValues[4].lowercase().takeIf { it.isNotEmpty() }?.let { dayNames[it] }
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = interval,
                unit = unitOf(match.groupValues[2]),
                daysOfWeek = setOfNotNull(dow),
            )
        }

        // "every monday", "every mon and thu"
        firstMatch(
            Regex("""\bevery\s+($dayNamePattern)s?(\s*(,|and)\s*($dayNamePattern)s?)*\b""", RegexOption.IGNORE_CASE),
        )?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val days = Regex(dayNamePattern, RegexOption.IGNORE_CASE)
                .findAll(match.value)
                .mapNotNull { dayNames[it.value.lowercase()] }
                .toSet()
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.WEEK,
                daysOfWeek = days,
            )
        }

        // "daily", "weekly", "monthly", "yearly"
        firstMatch(Regex("""\b(daily|weekly|monthly|yearly|annually)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val unit = when (match.groupValues[1].lowercase()) {
                "daily" -> RecurrenceUnit.DAY
                "weekly" -> RecurrenceUnit.WEEK
                "monthly" -> RecurrenceUnit.MONTH
                else -> RecurrenceUnit.YEAR
            }
            return RecurrenceRule(mode = RecurrenceMode.SCHEDULE, interval = 1, unit = unit)
        }

        return null
    }

    private fun parseDate(
        today: LocalDate,
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): LocalDate? {
        firstMatch(Regex("""\b(today|tonight)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today
        }
        firstMatch(Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today.plusDays(1)
        }
        firstMatch(Regex("""\bin\s+(\d+)\s+(day|week|month)s?\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            val amount = match.groupValues[1].toLong()
            return when (unitOf(match.groupValues[2])) {
                RecurrenceUnit.DAY -> today.plusDays(amount)
                RecurrenceUnit.WEEK -> today.plusWeeks(amount)
                else -> today.plusMonths(amount)
            }
        }
        firstMatch(Regex("""\b(\d{4})-(\d{2})-(\d{2})\b"""))?.let { match ->
            claim(match.range, TokenKind.DATE)
            return runCatching {
                LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            }.getOrNull()
        }
        // German style: 24.12. or 24.12.2026
        firstMatch(Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})?"""))?.let { match ->
            claim(match.range, TokenKind.DATE)
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].toIntOrNull()
            return buildDate(today, year, month, day)
        }
        // "24 Dec" / "Dec 24"
        firstMatch(Regex("""\b(\d{1,2})\.?\s+($monthNamePattern)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            val month = monthNames[match.groupValues[2].lowercase()] ?: return null
            return buildDate(today, null, month, match.groupValues[1].toInt())
        }
        firstMatch(Regex("""\b($monthNamePattern)\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            val month = monthNames[match.groupValues[1].lowercase()] ?: return null
            return buildDate(today, null, month, match.groupValues[2].toInt())
        }
        firstMatch(Regex("""\b(next\s+)?($dayNamePattern)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            val day = dayNames[match.groupValues[2].lowercase()] ?: return null
            return today.with(TemporalAdjusters.next(day))
        }
        firstMatch(Regex("""\bnext\s+week\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today.plusWeeks(1)
        }
        return null
    }

    private fun parseTime(
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): LocalTime? {
        firstMatch(Regex("""\b(?:at\s+)?(\d{1,2}):(\d{2})\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.TIME)
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour > 23 || minute > 59) return null
            return LocalTime.of(hour, minute)
        }
        firstMatch(Regex("""\b(?:at\s+)?(\d{1,2})\s*(am|pm)\b""", RegexOption.IGNORE_CASE))?.let { match ->
            claim(match.range, TokenKind.TIME)
            val raw = match.groupValues[1].toInt()
            if (raw !in 1..12) return null
            val hour = when {
                match.groupValues[2].equals("pm", ignoreCase = true) && raw < 12 -> raw + 12
                match.groupValues[2].equals("am", ignoreCase = true) && raw == 12 -> 0
                else -> raw
            }
            return LocalTime.of(hour, 0)
        }
        return null
    }

    private fun buildDate(today: LocalDate, year: Int?, month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val candidate = LocalDate.of(today.year, month, day)
                if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            }
        }.getOrNull()
    }

    private fun unitOf(word: String): RecurrenceUnit = when (word.lowercase().removeSuffix("s")) {
        "day" -> RecurrenceUnit.DAY
        "week" -> RecurrenceUnit.WEEK
        "month" -> RecurrenceUnit.MONTH
        else -> RecurrenceUnit.YEAR
    }

    private fun buildTitle(input: String, consumed: List<IntRange>): String {
        val builder = StringBuilder()
        input.forEachIndexed { index, char ->
            val eaten = consumed.any { index in it }
            if (!eaten) builder.append(char)
        }
        return builder.toString()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(',', '·', '-')
            .trim()
    }
}
