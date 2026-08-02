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
 *
 * The keywords themselves live in [QuickAddLexicon], so the same grammar reads
 * "jeden Tag Frühstück zubereiten" once the German lexicon is passed in.
 */
object QuickAddParser {

    fun parse(
        input: String,
        projects: List<Project> = emptyList(),
        today: LocalDate = LocalDate.now(),
        lexicon: QuickAddLexicon = QuickAddLexicon.English,
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
        val recurrence = parseRecurrence(lexicon, find, take)

        // ── Due date ───────────────────────────────────────────────────────────────
        val dueDate = parseDate(lexicon, today, find, take)

        // ── Time ───────────────────────────────────────────────────────────────────
        val dueTime = parseTime(lexicon, find, take)

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
        lexicon: QuickAddLexicon,
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): RecurrenceRule? {
        val patterns = lexicon.patterns

        // "3 days after done" / "2 Wochen nach Erledigung"
        firstMatch(patterns.afterCompletion)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            return RecurrenceRule(
                mode = RecurrenceMode.AFTER_COMPLETION,
                interval = match.group("count")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                unit = lexicon.unitOf(match.group("unit")),
            )
        }

        // "every 1st", "jeden 15. des Monats"
        firstMatch(patterns.everyOrdinal)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.MONTH,
                monthlyMode = MonthlyMode.DAY_OF_MONTH,
                dayOfMonth = (match.group("dom")?.toIntOrNull() ?: 1).coerceIn(1, 31),
            )
        }

        // "every last weekday" / "jeden letzten Tag"
        firstMatch(patterns.everyLast)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val what = match.group("what").orEmpty().lowercase()
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.MONTH,
                monthlyMode = if (what in lexicon.workday) {
                    MonthlyMode.LAST_WEEKDAY
                } else {
                    MonthlyMode.LAST_DAY
                },
            )
        }

        // "every 2 weeks on thu", "alle 2 Wochen am Donnerstag", "every other day"
        firstMatch(patterns.everyInterval)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val interval = when {
                match.group("doubled") != null -> 2
                else -> match.group("count")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            }
            val dow = match.group("dow")?.let { lexicon.dayOf(it) }
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = interval,
                unit = lexicon.unitOf(match.group("unit")),
                daysOfWeek = setOfNotNull(dow),
            )
        }

        // "every monday", "jeden Mo und Do"
        firstMatch(patterns.everyWeekday)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val days = patterns.singleDayName
                .findAll(match.group("days").orEmpty())
                .mapNotNull { lexicon.dayOf(it.value) }
                .toSet()
            return RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.WEEK,
                daysOfWeek = days,
            )
        }

        // "daily", "täglich"
        firstMatch(patterns.period)?.let { match ->
            claim(match.range, TokenKind.RECURRENCE)
            val unit = lexicon.periods[match.group("word")?.lowercase()] ?: RecurrenceUnit.DAY
            return RecurrenceRule(mode = RecurrenceMode.SCHEDULE, interval = 1, unit = unit)
        }

        return null
    }

    private fun parseDate(
        lexicon: QuickAddLexicon,
        today: LocalDate,
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): LocalDate? {
        val patterns = lexicon.patterns

        firstMatch(patterns.today)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today
        }
        // before "tomorrow", which sits inside "day after tomorrow"
        firstMatch(patterns.dayAfterTomorrow)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today.plusDays(2)
        }
        firstMatch(patterns.tomorrow)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today.plusDays(1)
        }
        firstMatch(patterns.within)?.let { match ->
            claim(match.range, TokenKind.DATE)
            val amount = match.group("count")?.toLongOrNull() ?: return null
            return when (lexicon.unitOf(match.group("unit"))) {
                RecurrenceUnit.DAY -> today.plusDays(amount)
                RecurrenceUnit.WEEK -> today.plusWeeks(amount)
                RecurrenceUnit.MONTH -> today.plusMonths(amount)
                RecurrenceUnit.YEAR -> today.plusYears(amount)
            }
        }
        firstMatch(patterns.isoDate)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return runCatching {
                LocalDate.of(
                    match.group("year")!!.toInt(),
                    match.group("month")!!.toInt(),
                    match.group("day")!!.toInt(),
                )
            }.getOrNull()
        }
        // German style: 24.12. or 24.12.2026
        firstMatch(patterns.numericDate)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return buildDate(
                today = today,
                year = match.group("year")?.toIntOrNull(),
                month = match.group("month")?.toIntOrNull() ?: return null,
                day = match.group("day")?.toIntOrNull() ?: return null,
            )
        }
        // "24 Dec" / "24. Dez"
        firstMatch(patterns.dayMonth)?.let { match ->
            claim(match.range, TokenKind.DATE)
            val month = lexicon.monthOf(match.group("month")) ?: return null
            return buildDate(today, null, month, match.group("day")?.toIntOrNull() ?: return null)
        }
        // "Dec 24"
        firstMatch(patterns.monthDay)?.let { match ->
            claim(match.range, TokenKind.DATE)
            val month = lexicon.monthOf(match.group("month")) ?: return null
            return buildDate(today, null, month, match.group("day")?.toIntOrNull() ?: return null)
        }
        firstMatch(patterns.weekdayDate)?.let { match ->
            claim(match.range, TokenKind.DATE)
            val day = lexicon.dayOf(match.group("dow")) ?: return null
            return today.with(TemporalAdjusters.next(day))
        }
        firstMatch(patterns.nextWeek)?.let { match ->
            claim(match.range, TokenKind.DATE)
            return today.plusWeeks(1)
        }
        return null
    }

    private fun parseTime(
        lexicon: QuickAddLexicon,
        firstMatch: (Regex) -> MatchResult?,
        claim: (IntRange, TokenKind) -> Unit,
    ): LocalTime? {
        val patterns = lexicon.patterns

        firstMatch(patterns.clockTime)?.let { match ->
            claim(match.range, TokenKind.TIME)
            val hour = match.group("hour")?.toIntOrNull() ?: return null
            val minute = match.group("minute")?.toIntOrNull() ?: return null
            if (hour > 23 || minute > 59) return null
            return LocalTime.of(hour, minute)
        }
        // "um 17 Uhr"
        firstMatch(patterns.hourClock)?.let { match ->
            claim(match.range, TokenKind.TIME)
            val hour = match.group("hour")?.toIntOrNull() ?: return null
            if (hour > 23) return null
            return LocalTime.of(hour, 0)
        }
        firstMatch(patterns.amPm)?.let { match ->
            claim(match.range, TokenKind.TIME)
            val raw = match.group("hour")?.toIntOrNull() ?: return null
            if (raw !in 1..12) return null
            val pm = match.group("half").equals("pm", ignoreCase = true)
            val hour = when {
                pm && raw < 12 -> raw + 12
                !pm && raw == 12 -> 0
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

    /** Named groups read as null when the group did not take part in the match. */
    private fun MatchResult.group(name: String): String? = groups[name]?.value

    private fun QuickAddLexicon.unitOf(word: String?): RecurrenceUnit =
        units[word?.lowercase()] ?: RecurrenceUnit.DAY

    private fun QuickAddLexicon.dayOf(word: String?): DayOfWeek? =
        dayNames[word?.lowercase()?.trimEnd('.')]

    private fun QuickAddLexicon.monthOf(word: String?): Int? =
        monthNames[word?.lowercase()?.trimEnd('.')]
}
