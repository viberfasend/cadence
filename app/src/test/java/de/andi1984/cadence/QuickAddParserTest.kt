package de.andi1984.cadence

import de.andi1984.cadence.data.db.RecurrenceCodec
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.parse.QuickAddParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class QuickAddParserTest {

    private val today = LocalDate.of(2026, 8, 12)

    private val projects = listOf(
        Project(id = 1L, name = "Home", colorHex = "#A1560A"),
        Project(id = 2L, name = "Q3 Launch", colorHex = "#3E6373", parentId = 3L),
    )

    @Test
    fun `the example from the design parses into four facts`() {
        val parsed = QuickAddParser.parse("Pay rent every 1st !p2 #Home", projects, today)

        assertEquals("Pay rent", parsed.title)
        assertEquals(Priority.P2, parsed.priority)
        assertEquals(1L, parsed.projectId)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.dueDate)
        assertEquals(RecurrenceUnit.MONTH, parsed.recurrence?.unit)
        assertEquals(MonthlyMode.DAY_OF_MONTH, parsed.recurrence?.monthlyMode)
        assertEquals(1, parsed.recurrence?.dayOfMonth)
        // priority, project and the recurrence phrase are highlighted in the typed line
        assertEquals(3, parsed.spans.size)
    }

    @Test
    fun `a project written without spaces still matches`() {
        val parsed = QuickAddParser.parse("Ship the notes #Q3Launch", projects, today)
        assertEquals(2L, parsed.projectId)
        assertEquals("Ship the notes", parsed.title)
    }

    @Test
    fun `after completion rules are recognised`() {
        val parsed = QuickAddParser.parse("Water the plants 3 days after done", projects, today)

        assertEquals("Water the plants", parsed.title)
        assertEquals(RecurrenceMode.AFTER_COMPLETION, parsed.recurrence?.mode)
        assertEquals(3, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, parsed.recurrence?.unit)
        assertEquals(today, parsed.dueDate)
    }

    @Test
    fun `relative dates and times`() {
        val parsed = QuickAddParser.parse("Call the dentist tomorrow at 17:00 !p1", projects, today)

        assertEquals("Call the dentist", parsed.title)
        assertEquals(today.plusDays(1), parsed.dueDate)
        assertEquals(LocalTime.of(17, 0), parsed.dueTime)
        assertEquals(Priority.P1, parsed.priority)
    }

    @Test
    fun `every two weeks on a weekday`() {
        val parsed = QuickAddParser.parse("Take out recycling every 2 weeks on thu", projects, today)

        assertEquals("Take out recycling", parsed.title)
        assertEquals(2, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.WEEK, parsed.recurrence?.unit)
        assertEquals(setOf(DayOfWeek.THURSDAY), parsed.recurrence?.daysOfWeek)
    }

    @Test
    fun `every weekday name without an interval`() {
        val parsed = QuickAddParser.parse("Weekly review every friday", projects, today)
        assertEquals("Weekly review", parsed.title)
        assertEquals(RecurrenceUnit.WEEK, parsed.recurrence?.unit)
        assertEquals(setOf(DayOfWeek.FRIDAY), parsed.recurrence?.daysOfWeek)
    }

    @Test
    fun `plain text stays plain`() {
        val parsed = QuickAddParser.parse("Buy milk", projects, today)

        assertEquals("Buy milk", parsed.title)
        assertNull(parsed.dueDate)
        assertNull(parsed.priority)
        assertNull(parsed.recurrence)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun `german dates`() {
        val parsed = QuickAddParser.parse("Steuer 24.12.", projects, today)
        assertEquals("Steuer", parsed.title)
        assertEquals(LocalDate.of(2026, 12, 24), parsed.dueDate)
    }

    @Test
    fun `in n days`() {
        val parsed = QuickAddParser.parse("Chase the invoice in 3 days", projects, today)
        assertEquals("Chase the invoice", parsed.title)
        assertEquals(today.plusDays(3), parsed.dueDate)
    }

    @Test
    fun `recurrence rules survive a round trip through the database column`() {
        val rule = RecurrenceRule(
            mode = RecurrenceMode.SCHEDULE,
            interval = 3,
            unit = RecurrenceUnit.MONTH,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            monthlyMode = MonthlyMode.NTH_WEEKDAY,
            dayOfMonth = 15,
            nthWeek = 2,
            nthDayOfWeek = DayOfWeek.TUESDAY,
            keepMissed = false,
        )
        assertEquals(rule, RecurrenceCodec.decode(RecurrenceCodec.encode(rule)))
        assertNull(RecurrenceCodec.encode(null))
        assertNull(RecurrenceCodec.decode(null))
        assertNull(RecurrenceCodec.decode("garbage"))
    }
}
