package de.andi1984.cadence

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.parse.QuickAddLexicon
import de.andi1984.cadence.domain.parse.QuickAddParser
import de.andi1984.cadence.domain.parse.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class QuickAddParserTest {

    private val today = LocalDate.of(2026, 8, 12)
    private val german = QuickAddLexicon.forLocale(Locale.GERMAN)

    private val projects = listOf(
        Project(id = "1", name = "Home", colorHex = "#A1560A"),
        Project(id = "2", name = "Q3 Launch", colorHex = "#3E6373", parentId = "3"),
    )

    @Test
    fun `the example from the design parses into four facts`() {
        val parsed = QuickAddParser.parse("Pay rent every 1st !p2 #Home", projects, today = today)

        assertEquals("Pay rent", parsed.title)
        assertEquals(Priority.P2, parsed.priority)
        assertEquals("1", parsed.projectId)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.dueDate)
        assertEquals(RecurrenceUnit.MONTH, parsed.recurrence?.unit)
        assertEquals(MonthlyMode.DAY_OF_MONTH, parsed.recurrence?.monthlyMode)
        assertEquals(1, parsed.recurrence?.dayOfMonth)
        // priority, project and the recurrence phrase are highlighted in the typed line
        assertEquals(3, parsed.spans.size)
    }

    @Test
    fun `a project written without spaces still matches`() {
        val parsed = QuickAddParser.parse("Ship the notes #Q3Launch", projects, today = today)
        assertEquals("2", parsed.projectId)
        assertEquals("Ship the notes", parsed.title)
    }

    @Test
    fun `after completion rules are recognised`() {
        val parsed = QuickAddParser.parse("Water the plants 3 days after done", projects, today = today)

        assertEquals("Water the plants", parsed.title)
        assertEquals(RecurrenceMode.AFTER_COMPLETION, parsed.recurrence?.mode)
        assertEquals(3, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, parsed.recurrence?.unit)
        assertEquals(today, parsed.dueDate)
    }

    @Test
    fun `relative dates and times`() {
        val parsed = QuickAddParser.parse("Call the dentist tomorrow at 17:00 !p1", projects, today = today)

        assertEquals("Call the dentist", parsed.title)
        assertEquals(today.plusDays(1), parsed.dueDate)
        assertEquals(LocalTime.of(17, 0), parsed.dueTime)
        assertEquals(Priority.P1, parsed.priority)
    }

    @Test
    fun `every two weeks on a weekday`() {
        val parsed = QuickAddParser.parse("Take out recycling every 2 weeks on thu", projects, today = today)

        assertEquals("Take out recycling", parsed.title)
        assertEquals(2, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.WEEK, parsed.recurrence?.unit)
        assertEquals(setOf(DayOfWeek.THURSDAY), parsed.recurrence?.daysOfWeek)
    }

    @Test
    fun `every weekday name without an interval`() {
        val parsed = QuickAddParser.parse("Weekly review every friday", projects, today = today)
        assertEquals("Weekly review", parsed.title)
        assertEquals(RecurrenceUnit.WEEK, parsed.recurrence?.unit)
        assertEquals(setOf(DayOfWeek.FRIDAY), parsed.recurrence?.daysOfWeek)
    }

    @Test
    fun `plain text stays plain`() {
        val parsed = QuickAddParser.parse("Buy milk", projects, today = today)

        assertEquals("Buy milk", parsed.title)
        assertNull(parsed.dueDate)
        assertNull(parsed.priority)
        assertNull(parsed.recurrence)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun `german dates`() {
        val parsed = QuickAddParser.parse("Steuer 24.12.", projects, today = today)
        assertEquals("Steuer", parsed.title)
        assertEquals(LocalDate.of(2026, 12, 24), parsed.dueDate)
    }

    @Test
    fun `in n days`() {
        val parsed = QuickAddParser.parse("Chase the invoice in 3 days", projects, today = today)
        assertEquals("Chase the invoice", parsed.title)
        assertEquals(today.plusDays(3), parsed.dueDate)
    }

    @Test
    fun `german recurrence needs no english keywords`() {
        val parsed = QuickAddParser.parse("jeden Tag Frühstück zubereiten", projects, today = today, lexicon = german)

        assertEquals("Frühstück zubereiten", parsed.title)
        assertEquals(RecurrenceMode.SCHEDULE, parsed.recurrence?.mode)
        assertEquals(1, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, parsed.recurrence?.unit)
    }

    @Test
    fun `german interval rules with a weekday`() {
        val parsed = QuickAddParser.parse("Müll rausbringen alle 2 Wochen am Donnerstag", projects, today = today, lexicon = german)

        assertEquals("Müll rausbringen", parsed.title)
        assertEquals(2, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.WEEK, parsed.recurrence?.unit)
        assertEquals(setOf(DayOfWeek.THURSDAY), parsed.recurrence?.daysOfWeek)
    }

    @Test
    fun `german ordinal rules`() {
        val parsed = QuickAddParser.parse("Miete zahlen jeden 1.", projects, today = today, lexicon = german)

        assertEquals("Miete zahlen", parsed.title)
        assertEquals(RecurrenceUnit.MONTH, parsed.recurrence?.unit)
        assertEquals(MonthlyMode.DAY_OF_MONTH, parsed.recurrence?.monthlyMode)
        assertEquals(1, parsed.recurrence?.dayOfMonth)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.dueDate)
    }

    @Test
    fun `german after completion rules`() {
        val parsed = QuickAddParser.parse("Blumen gießen 3 Tage nach Erledigung", projects, today = today, lexicon = german)

        assertEquals("Blumen gießen", parsed.title)
        assertEquals(RecurrenceMode.AFTER_COMPLETION, parsed.recurrence?.mode)
        assertEquals(3, parsed.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, parsed.recurrence?.unit)
        assertEquals(today, parsed.dueDate)
    }

    @Test
    fun `german adverbs and last-day rules`() {
        val weekly = QuickAddParser.parse("Küche putzen wöchentlich", projects, today = today, lexicon = german)
        assertEquals("Küche putzen", weekly.title)
        assertEquals(RecurrenceUnit.WEEK, weekly.recurrence?.unit)

        val lastWorkday = QuickAddParser.parse("Bericht jeden letzten Werktag", projects, today = today, lexicon = german)
        assertEquals("Bericht", lastWorkday.title)
        assertEquals(MonthlyMode.LAST_WEEKDAY, lastWorkday.recurrence?.monthlyMode)
    }

    @Test
    fun `german dates and times`() {
        val tomorrow = QuickAddParser.parse("Zahnarzt anrufen morgen um 17:00", projects, today = today, lexicon = german)
        assertEquals("Zahnarzt anrufen", tomorrow.title)
        assertEquals(today.plusDays(1), tomorrow.dueDate)
        assertEquals(LocalTime.of(17, 0), tomorrow.dueTime)

        val overmorrow = QuickAddParser.parse("Rückruf übermorgen um 9 Uhr", projects, today = today, lexicon = german)
        assertEquals("Rückruf", overmorrow.title)
        assertEquals(today.plusDays(2), overmorrow.dueDate)
        assertEquals(LocalTime.of(9, 0), overmorrow.dueTime)

        val friday = QuickAddParser.parse("Bericht nächsten Freitag", projects, today = today, lexicon = german)
        assertEquals("Bericht", friday.title)
        assertEquals(LocalDate.of(2026, 8, 14), friday.dueDate)

        val inDays = QuickAddParser.parse("Rechnung nachfassen in 3 Tagen", projects, today = today, lexicon = german)
        assertEquals("Rechnung nachfassen", inDays.title)
        assertEquals(today.plusDays(3), inDays.dueDate)
    }

    @Test
    fun `nth weekday of the month`() {
        val parsed = QuickAddParser.parse("Team sync every 2nd monday", projects, today = today)

        assertEquals("Team sync", parsed.title)
        assertEquals(RecurrenceUnit.MONTH, parsed.recurrence?.unit)
        assertEquals(MonthlyMode.NTH_WEEKDAY, parsed.recurrence?.monthlyMode)
        assertEquals(2, parsed.recurrence?.nthWeek)
        assertEquals(DayOfWeek.MONDAY, parsed.recurrence?.nthDayOfWeek)
        // Second Monday in September, the first one after 12 Aug having passed on 10 Aug.
        assertEquals(LocalDate.of(2026, 9, 14), parsed.dueDate)
    }

    @Test
    fun `nth weekday spelled out and in german`() {
        val spelled = QuickAddParser.parse("Team sync every second monday", projects, today = today)
        assertEquals("Team sync", spelled.title)
        assertEquals(2, spelled.recurrence?.nthWeek)
        assertEquals(DayOfWeek.MONDAY, spelled.recurrence?.nthDayOfWeek)

        val digits = QuickAddParser.parse("Jour fixe jeden 2. Montag", projects, today = today, lexicon = german)
        assertEquals("Jour fixe", digits.title)
        assertEquals(MonthlyMode.NTH_WEEKDAY, digits.recurrence?.monthlyMode)
        assertEquals(2, digits.recurrence?.nthWeek)
        assertEquals(DayOfWeek.MONDAY, digits.recurrence?.nthDayOfWeek)

        val words = QuickAddParser.parse("Jour fixe jeden zweiten Montag des Monats", projects, today = today, lexicon = german)
        assertEquals("Jour fixe", words.title)
        assertEquals(2, words.recurrence?.nthWeek)
        assertEquals(DayOfWeek.MONDAY, words.recurrence?.nthDayOfWeek)
    }

    @Test
    fun `last named weekday of the month`() {
        val english = QuickAddParser.parse("Retro every last friday", projects, today = today)
        assertEquals("Retro", english.title)
        assertEquals(MonthlyMode.NTH_WEEKDAY, english.recurrence?.monthlyMode)
        // The engine reads 5 as "the last one in the month".
        assertEquals(5, english.recurrence?.nthWeek)
        assertEquals(DayOfWeek.FRIDAY, english.recurrence?.nthDayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 28), english.dueDate)

        val german = QuickAddParser.parse("Retro jeden letzten Freitag", projects, today = today, lexicon = german)
        assertEquals("Retro", german.title)
        assertEquals(5, german.recurrence?.nthWeek)
        assertEquals(DayOfWeek.FRIDAY, german.recurrence?.nthDayOfWeek)
    }

    @Test
    fun `spelled out counts read like digits`() {
        val english = QuickAddParser.parse("Water plants every three days", projects, today = today)
        assertEquals("Water plants", english.title)
        assertEquals(3, english.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, english.recurrence?.unit)

        val german = QuickAddParser.parse("Blumen gießen alle drei Tage", projects, today = today, lexicon = german)
        assertEquals("Blumen gießen", german.title)
        assertEquals(3, german.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, german.recurrence?.unit)
    }

    @Test
    fun `spelled out counts in relative dates and completion rules`() {
        val inDays = QuickAddParser.parse("Rechnung nachfassen in drei Tagen", projects, today = today, lexicon = german)
        assertEquals("Rechnung nachfassen", inDays.title)
        assertEquals(today.plusDays(3), inDays.dueDate)

        val inWeek = QuickAddParser.parse("Follow up in one week", projects, today = today)
        assertEquals("Follow up", inWeek.title)
        assertEquals(today.plusWeeks(1), inWeek.dueDate)

        val afterDone = QuickAddParser.parse("Blumen gießen drei Tage nach Erledigung", projects, today = today, lexicon = german)
        assertEquals("Blumen gießen", afterDone.title)
        assertEquals(RecurrenceMode.AFTER_COMPLETION, afterDone.recurrence?.mode)
        assertEquals(3, afterDone.recurrence?.interval)
    }

    @Test
    fun `a spelled out ordinal still needs the month to be a day-of-month rule`() {
        val dayOfMonth = QuickAddParser.parse("Miete zahlen jeden ersten des Monats", projects, today = today, lexicon = german)
        assertEquals("Miete zahlen", dayOfMonth.title)
        assertEquals(MonthlyMode.DAY_OF_MONTH, dayOfMonth.recurrence?.monthlyMode)
        assertEquals(1, dayOfMonth.recurrence?.dayOfMonth)

        // Without it, the same shape is an interval: every third *day*, not the 3rd of the month.
        val interval = QuickAddParser.parse("Blumen gießen jeden dritten Tag", projects, today = today, lexicon = german)
        assertEquals("Blumen gießen", interval.title)
        assertEquals(RecurrenceUnit.DAY, interval.recurrence?.unit)
        assertEquals(3, interval.recurrence?.interval)
    }

    @Test
    fun `english keeps working inside the german lexicon`() {
        val parsed = QuickAddParser.parse("Pay rent every 1st !p2 #Home", projects, today = today, lexicon = german)

        assertEquals("Pay rent", parsed.title)
        assertEquals(Priority.P2, parsed.priority)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.dueDate)
    }

    @Test
    fun `plain german text stays plain`() {
        val parsed = QuickAddParser.parse("Milch kaufen", projects, today = today, lexicon = german)

        assertEquals("Milch kaufen", parsed.title)
        assertNull(parsed.dueDate)
        assertNull(parsed.recurrence)
        assertTrue(parsed.spans.isEmpty())
    }

    @Test
    fun `an unknown language still reads its own weekday names`() {
        val french = QuickAddLexicon.forLocale(Locale.FRENCH)
        val parsed = QuickAddParser.parse("Payer le loyer vendredi", projects, today = today, lexicon = french)

        assertEquals("Payer le loyer", parsed.title)
        assertEquals(LocalDate.of(2026, 8, 14), parsed.dueDate)
    }

    @Test
    fun `every shipped lexicon compiles its patterns`() {
        // A malformed alternation only shows up when the grammar is first used, which is inside
        // the quick-add sheet — parse one line per language so a bad pattern fails here instead.
        listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH).forEach { locale ->
            val parsed = QuickAddParser.parse("Test", projects, today = today, lexicon = QuickAddLexicon.forLocale(locale))
            assertEquals("Test", parsed.title)
        }
    }


    // ── Tags ───────────────────────────────────────────────────────────────────────

    private val tags = listOf(
        Tag(id = "g1", name = "Errand"),
        Tag(id = "g2", name = "Deep Work"),
    )

    @Test
    fun `a handle matches an existing tag and leaves the title clean`() {
        val parsed = QuickAddParser.parse("Post the parcel @errand", projects, tags, today)

        assertEquals("Post the parcel", parsed.title)
        assertEquals(listOf("g1"), parsed.tagIds)
        assertEquals(emptyList<String>(), parsed.newTagNames)
    }

    /** A tag may be called "Deep Work"; nobody types the space, because it would end the token. */
    @Test
    fun `a tag written without spaces still matches`() {
        val parsed = QuickAddParser.parse("Draft the ADR @deepwork", projects, tags, today)
        assertEquals(listOf("g2"), parsed.tagIds)
    }

    /** The one token kind that repeats: a task is filed once and labelled as often as it likes. */
    @Test
    fun `several handles all land, deduplicated`() {
        val parsed = QuickAddParser.parse("Post it @errand @deepwork @errand", projects, tags, today)

        assertEquals("Post it", parsed.title)
        assertEquals(listOf("g1", "g2"), parsed.tagIds)
    }

    @Test
    fun `a handle that matches nothing is reported as a tag to create`() {
        val parsed = QuickAddParser.parse("Chase the invoice @waiting", projects, tags, today)

        assertEquals("Chase the invoice", parsed.title)
        assertEquals(emptyList<String>(), parsed.tagIds)
        assertEquals(listOf("waiting"), parsed.newTagNames)
    }

    /**
     * The reason the `@` pass checks the character before it by hand rather than with `\b`: ICU
     * and `java.util.regex` disagree about word boundaries, and the tests run on the second while
     * the device runs on the first.
     */
    @Test
    fun `an email address in the title is not a tag`() {
        val parsed = QuickAddParser.parse("Reply to mail@example.com", projects, tags, today)

        assertEquals("Reply to mail@example.com", parsed.title)
        assertEquals(emptyList<String>(), parsed.tagIds)
        assertEquals(emptyList<String>(), parsed.newTagNames)
    }

    @Test
    fun `tags coexist with a project, a priority and a date`() {
        val parsed = QuickAddParser.parse(
            "Pay rent tomorrow !p2 #Home @errand",
            projects,
            tags,
            today,
        )

        assertEquals("Pay rent", parsed.title)
        assertEquals("1", parsed.projectId)
        assertEquals(listOf("g1"), parsed.tagIds)
        assertEquals(today.plusDays(1), parsed.dueDate)
    }

    @Test
    fun `a tag span is reported so the sheet can paint it`() {
        val parsed = QuickAddParser.parse("Post it @errand", projects, tags, today)

        assertEquals(listOf(TokenKind.TAG), parsed.spans.map { it.kind })
    }
}
