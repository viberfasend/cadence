package de.andi1984.cadence

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.recurrence.MonthlyPhrase
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import de.andi1984.cadence.domain.recurrence.RecurrenceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class RecurrenceEngineTest {

    @Test
    fun `monthly on a fixed day rolls into the next month`() {
        val rule = RecurrenceRule(
            unit = RecurrenceUnit.MONTH,
            monthlyMode = MonthlyMode.DAY_OF_MONTH,
            dayOfMonth = 1,
        )
        assertEquals(
            LocalDate.of(2026, 9, 1),
            RecurrenceEngine.nextAfter(rule, LocalDate.of(2026, 8, 12)),
        )
    }

    @Test
    fun `a day of month past the end of a short month is clamped`() {
        val rule = RecurrenceRule(
            unit = RecurrenceUnit.MONTH,
            monthlyMode = MonthlyMode.DAY_OF_MONTH,
            dayOfMonth = 31,
        )
        assertEquals(
            LocalDate.of(2026, 2, 28),
            RecurrenceEngine.nextAfter(rule, LocalDate.of(2026, 1, 31)),
        )
    }

    @Test
    fun `last day of month`() {
        val rule = RecurrenceRule(unit = RecurrenceUnit.MONTH, monthlyMode = MonthlyMode.LAST_DAY)
        assertEquals(
            LocalDate.of(2026, 1, 31),
            RecurrenceEngine.nextAfter(rule, LocalDate.of(2026, 1, 15)),
        )
    }

    @Test
    fun `last weekday never lands on a weekend and is the last one in the month`() {
        val rule = RecurrenceRule(
            interval = 3,
            unit = RecurrenceUnit.MONTH,
            monthlyMode = MonthlyMode.LAST_WEEKDAY,
        )
        val next = RecurrenceEngine.nextAfter(rule, LocalDate.of(2026, 1, 5))
        assertTrue(next.dayOfWeek != DayOfWeek.SATURDAY && next.dayOfWeek != DayOfWeek.SUNDAY)
        val endOfMonth = YearMonth.from(next).atEndOfMonth()
        assertTrue(ChronoUnit.DAYS.between(next, endOfMonth) <= 2)
    }

    @Test
    fun `nth weekday of the month`() {
        val rule = RecurrenceRule(
            unit = RecurrenceUnit.MONTH,
            monthlyMode = MonthlyMode.NTH_WEEKDAY,
            nthWeek = 2,
            nthDayOfWeek = DayOfWeek.TUESDAY,
        )
        val next = RecurrenceEngine.nextAfter(rule, LocalDate.of(2026, 3, 20))
        assertEquals(DayOfWeek.TUESDAY, next.dayOfWeek)
        assertTrue(next.dayOfMonth in 8..14)
    }

    @Test
    fun `every two weeks keeps the weekday and skips a week`() {
        val thursday = LocalDate.of(2026, 8, 13).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY))
        val rule = RecurrenceRule(
            interval = 2,
            unit = RecurrenceUnit.WEEK,
            daysOfWeek = setOf(DayOfWeek.THURSDAY),
        )
        val next = RecurrenceEngine.nextAfter(rule, thursday)
        assertEquals(DayOfWeek.THURSDAY, next.dayOfWeek)
        assertEquals(14L, ChronoUnit.DAYS.between(thursday, next))
    }

    @Test
    fun `weekly with several days picks the next one in the same week`() {
        val rule = RecurrenceRule(
            unit = RecurrenceUnit.WEEK,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        )
        val monday = LocalDate.of(2026, 8, 10)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 13), RecurrenceEngine.nextAfter(rule, monday))
    }

    @Test
    fun `after completion counts from the day it was finished`() {
        val rule = RecurrenceRule(
            mode = RecurrenceMode.AFTER_COMPLETION,
            interval = 3,
            unit = RecurrenceUnit.DAY,
        )
        assertEquals(
            LocalDate.of(2026, 8, 15),
            RecurrenceEngine.dueDateAfterCompletion(
                rule = rule,
                previousDue = LocalDate.of(2026, 8, 1),
                completedOn = LocalDate.of(2026, 8, 12),
            ),
        )
    }

    @Test
    fun `keeping missed instances leaves the series where it was`() {
        val rule = RecurrenceRule(unit = RecurrenceUnit.DAY, keepMissed = true)
        assertEquals(
            LocalDate.of(2026, 7, 2),
            RecurrenceEngine.dueDateAfterCompletion(
                rule = rule,
                previousDue = LocalDate.of(2026, 7, 1),
                completedOn = LocalDate.of(2026, 8, 1),
            ),
        )
    }

    @Test
    fun `dropping missed instances catches the series up`() {
        val rule = RecurrenceRule(unit = RecurrenceUnit.DAY, keepMissed = false)
        assertEquals(
            LocalDate.of(2026, 8, 1),
            RecurrenceEngine.dueDateAfterCompletion(
                rule = rule,
                previousDue = LocalDate.of(2026, 7, 1),
                completedOn = LocalDate.of(2026, 8, 1),
            ),
        )
    }

    @Test
    fun `a default rule catches an overdue series up to the next future occurrence`() {
        // keepMissed defaults to off, so a task completed well past its due date hands back the
        // next occurrence in the future rather than another overdue one a single step on.
        val rule = RecurrenceRule(unit = RecurrenceUnit.DAY)
        assertEquals(
            LocalDate.of(2026, 8, 1),
            RecurrenceEngine.dueDateAfterCompletion(
                rule = rule,
                previousDue = LocalDate.of(2026, 3, 4), // ~150 days before completion
                completedOn = LocalDate.of(2026, 8, 1),
            ),
        )
    }

    @Test
    fun `summaries carry the pieces the wording needs`() {
        assertEquals(
            RecurrenceSummary.Schedule(
                interval = 1,
                unit = RecurrenceUnit.MONTH,
                monthly = MonthlyPhrase.DayOfMonth(1),
            ),
            RecurrenceEngine.summarize(
                RecurrenceRule(
                    unit = RecurrenceUnit.MONTH,
                    monthlyMode = MonthlyMode.DAY_OF_MONTH,
                    dayOfMonth = 1,
                ),
            ),
        )
        assertEquals(
            RecurrenceSummary.Schedule(
                interval = 3,
                unit = RecurrenceUnit.MONTH,
                monthly = MonthlyPhrase.LastWeekday,
            ),
            RecurrenceEngine.summarize(
                RecurrenceRule(
                    interval = 3,
                    unit = RecurrenceUnit.MONTH,
                    monthlyMode = MonthlyMode.LAST_WEEKDAY,
                ),
            ),
        )
        assertEquals(
            RecurrenceSummary.AfterCompletion(interval = 3, unit = RecurrenceUnit.DAY),
            RecurrenceEngine.summarize(
                RecurrenceRule(
                    mode = RecurrenceMode.AFTER_COMPLETION,
                    interval = 3,
                    unit = RecurrenceUnit.DAY,
                ),
            ),
        )
        assertEquals(
            RecurrenceSummary.Schedule(interval = 1, unit = RecurrenceUnit.DAY),
            RecurrenceEngine.summarize(RecurrenceRule(unit = RecurrenceUnit.DAY)),
        )
    }

    @Test
    fun `weekly summaries keep the selected days in order`() {
        val summary = RecurrenceEngine.summarize(
            RecurrenceRule(
                interval = 2,
                unit = RecurrenceUnit.WEEK,
                daysOfWeek = setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY),
            ),
        )
        assertEquals(
            RecurrenceSummary.Schedule(
                interval = 2,
                unit = RecurrenceUnit.WEEK,
                daysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            ),
            summary,
        )
    }

    @Test
    fun `the preview returns three increasing dates`() {
        val rule = RecurrenceRule(
            unit = RecurrenceUnit.MONTH,
            monthlyMode = MonthlyMode.LAST_DAY,
        )
        val dates = RecurrenceEngine.nextOccurrences(rule, LocalDate.of(2026, 1, 10), 3)
        assertEquals(3, dates.size)
        assertTrue(dates[0] < dates[1] && dates[1] < dates[2])
    }
}
