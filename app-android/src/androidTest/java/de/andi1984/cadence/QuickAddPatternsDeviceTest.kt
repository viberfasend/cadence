package de.andi1984.cadence

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.parse.QuickAddLexicon
import de.andi1984.cadence.domain.parse.QuickAddParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The quick-add grammar compiled by the *device's* regex engine.
 *
 * Android compiles regexes with ICU, the JVM tests with `java.util.regex`, and the two disagree
 * — an inline flag ICU rejects passes every unit test and then throws inside the quick-add sheet
 * on a phone. Running the same grammar here is the only place that divergence shows up.
 *
 * Run with `./gradlew connectedDebugAndroidTest` (needs a device; CI has no emulator).
 */
@RunWith(AndroidJUnit4::class)
class QuickAddPatternsDeviceTest {

    private val today = LocalDate.of(2026, 8, 12)

    @Test
    fun everyShippedLexiconCompilesUnderIcu() {
        listOf(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH).forEach { locale ->
            val parsed = QuickAddParser.parse("Test", today = today, lexicon = QuickAddLexicon.forLocale(locale))
            assertEquals("Test", parsed.title)
        }
    }

    @Test
    fun nthWeekdayParsesUnderIcu() {
        val english = QuickAddParser.parse("Team sync every 2nd monday", today = today)
        assertEquals("Team sync", english.title)
        assertEquals(MonthlyMode.NTH_WEEKDAY, english.recurrence?.monthlyMode)
        assertEquals(2, english.recurrence?.nthWeek)
        assertEquals(DayOfWeek.MONDAY, english.recurrence?.nthDayOfWeek)

        val german = QuickAddParser.parse(
            "Jour fixe jeden letzten Freitag",
            today = today,
            lexicon = QuickAddLexicon.German,
        )
        assertEquals("Jour fixe", german.title)
        assertEquals(5, german.recurrence?.nthWeek)
        assertEquals(DayOfWeek.FRIDAY, german.recurrence?.nthDayOfWeek)
    }

    @Test
    fun spelledOutCountsParseUnderIcu() {
        val german = QuickAddParser.parse(
            "Blumen gießen alle drei Tage",
            today = today,
            lexicon = QuickAddLexicon.German,
        )
        assertEquals("Blumen gießen", german.title)
        assertEquals(3, german.recurrence?.interval)
        assertEquals(RecurrenceUnit.DAY, german.recurrence?.unit)

        // Non-ASCII keywords fold case only because they are written as two-case classes.
        val overmorrow = QuickAddParser.parse(
            "Rückruf Übermorgen",
            today = today,
            lexicon = QuickAddLexicon.German,
        )
        assertEquals("Rückruf", overmorrow.title)
        assertEquals(today.plusDays(2), overmorrow.dueDate)
    }
}
