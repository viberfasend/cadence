package de.andi1984.cadence.domain.parse

import de.andi1984.cadence.domain.model.RecurrenceUnit
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every language-specific word quick-add understands.
 *
 * The parser owns no vocabulary of its own — it builds its patterns from a lexicon, so teaching
 * quick-add a new language means adding words here, not touching the grammar. Lexicons compose,
 * and [forLocale] always folds [English] in, so `every 2 weeks` keeps working for someone running
 * the app in German.
 *
 * Weekday and month names are never listed: they come from `java.time` for the locale, which is
 * why a lexicon that only lists structure words still reads `nächsten Freitag` and `24. Dez`.
 *
 * This is still pure Kotlin with no Android imports — the words are grammar, not prose, and the
 * JVM tests exercise them directly.
 */
class QuickAddLexicon(
    /** Word → unit, every inflected form: `day`, `days`, `Tag`, `Tage`, `Tagen`. */
    val units: Map<String, RecurrenceUnit> = emptyMap(),
    /** Standalone adverbs: `daily`, `täglich`. */
    val periods: Map<String, RecurrenceUnit> = emptyMap(),
    /** Opens a recurrence: `every`, `jeden`, `alle`. */
    val every: Set<String> = emptySet(),
    /** Means "interval 2" without a digit: `other` in `every other day`, `jeden zweiten Tag`. */
    val doubled: Set<String> = emptySet(),
    /** Tail of `3 days after done` — full phrases, whitespace inside is flexible. */
    val afterCompletion: Set<String> = emptySet(),
    val today: Set<String> = emptySet(),
    val tomorrow: Set<String> = emptySet(),
    val dayAfterTomorrow: Set<String> = emptySet(),
    /** Opens a relative date: `in 3 days`, `in 3 Tagen`. */
    val within: Set<String> = emptySet(),
    val next: Set<String> = emptySet(),
    val week: Set<String> = emptySet(),
    /** `last` in `every last weekday`. */
    val last: Set<String> = emptySet(),
    val workday: Set<String> = emptySet(),
    /** `day` in `every last day` — the noun on its own. */
    val day: Set<String> = emptySet(),
    val ofTheMonth: Set<String> = emptySet(),
    /** Precedes a time or a date: `at`, `um`, `am`. */
    val at: Set<String> = emptySet(),
    /** Trails a bare hour: `Uhr`. English has none. */
    val clock: Set<String> = emptySet(),
    val and: Set<String> = emptySet(),
    /** Joins a weekday to a rule: `on thu`, `am Donnerstag`. */
    val on: Set<String> = emptySet(),
    /** What follows the number in an ordinal: `st`/`nd`/`rd`/`th`, or `.` in German. */
    val ordinal: Set<String> = emptySet(),
    /**
     * Spelled-out counts, cardinal and ordinal alike, mapped to their value: `three` and
     * `third` both read as 3, as do `drei` and `dritten`. Anywhere the grammar accepts a digit
     * it accepts these, so `alle drei Tage` and `every 3rd monday` take the same path.
     */
    val numbers: Map<String, Int> = emptyMap(),
    val dayNames: Map<String, DayOfWeek> = emptyMap(),
    val monthNames: Map<String, Int> = emptyMap(),
) {

    /**
     * Weekday words safe to read as a bare due date. Two-letter forms (`Mo`, `So`) are ordinary
     * German words too, so they only count inside a recurrence, where `jeden` precedes them.
     */
    val standaloneDayNames: Map<String, DayOfWeek> = dayNames.filterKeys { it.length >= 3 }

    internal val patterns: QuickAddPatterns by lazy { QuickAddPatterns(this) }

    operator fun plus(other: QuickAddLexicon): QuickAddLexicon = QuickAddLexicon(
        units = units + other.units,
        periods = periods + other.periods,
        every = every + other.every,
        doubled = doubled + other.doubled,
        afterCompletion = afterCompletion + other.afterCompletion,
        today = today + other.today,
        tomorrow = tomorrow + other.tomorrow,
        dayAfterTomorrow = dayAfterTomorrow + other.dayAfterTomorrow,
        within = within + other.within,
        next = next + other.next,
        week = week + other.week,
        last = last + other.last,
        workday = workday + other.workday,
        day = day + other.day,
        ofTheMonth = ofTheMonth + other.ofTheMonth,
        at = at + other.at,
        clock = clock + other.clock,
        and = and + other.and,
        on = on + other.on,
        ordinal = ordinal + other.ordinal,
        numbers = numbers + other.numbers,
        dayNames = dayNames + other.dayNames,
        monthNames = monthNames + other.monthNames,
    )

    companion object {

        private val englishWords = QuickAddLexicon(
            units = mapOf(
                "day" to RecurrenceUnit.DAY,
                "days" to RecurrenceUnit.DAY,
                "week" to RecurrenceUnit.WEEK,
                "weeks" to RecurrenceUnit.WEEK,
                "month" to RecurrenceUnit.MONTH,
                "months" to RecurrenceUnit.MONTH,
                "year" to RecurrenceUnit.YEAR,
                "years" to RecurrenceUnit.YEAR,
            ),
            periods = mapOf(
                "daily" to RecurrenceUnit.DAY,
                "weekly" to RecurrenceUnit.WEEK,
                "monthly" to RecurrenceUnit.MONTH,
                "yearly" to RecurrenceUnit.YEAR,
                "annually" to RecurrenceUnit.YEAR,
            ),
            every = setOf("every"),
            doubled = setOf("other"),
            afterCompletion = setOf(
                "after done",
                "after completion",
                "after finishing",
                "after i finish",
                "after i'm done",
                "after im done",
                "after it's done",
                "after its done",
            ),
            today = setOf("today", "tonight"),
            tomorrow = setOf("tomorrow"),
            dayAfterTomorrow = setOf("day after tomorrow"),
            within = setOf("in"),
            next = setOf("next"),
            week = setOf("week"),
            last = setOf("last"),
            workday = setOf("weekday"),
            day = setOf("day"),
            ofTheMonth = setOf("of the month"),
            at = setOf("at"),
            and = setOf("and"),
            on = setOf("on"),
            ordinal = setOf("st", "nd", "rd", "th"),
            numbers = numberWords(
                cardinals = listOf(
                    "one", "two", "three", "four", "five", "six",
                    "seven", "eight", "nine", "ten", "eleven", "twelve",
                ),
                ordinals = listOf(
                    "first", "second", "third", "fourth", "fifth", "sixth",
                    "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
                ),
            ),
        )

        private val germanWords = QuickAddLexicon(
            units = mapOf(
                "tag" to RecurrenceUnit.DAY,
                "tage" to RecurrenceUnit.DAY,
                "tagen" to RecurrenceUnit.DAY,
                "woche" to RecurrenceUnit.WEEK,
                "wochen" to RecurrenceUnit.WEEK,
                "monat" to RecurrenceUnit.MONTH,
                "monate" to RecurrenceUnit.MONTH,
                "monaten" to RecurrenceUnit.MONTH,
                "jahr" to RecurrenceUnit.YEAR,
                "jahre" to RecurrenceUnit.YEAR,
                "jahren" to RecurrenceUnit.YEAR,
            ),
            periods = mapOf(
                "täglich" to RecurrenceUnit.DAY,
                "taeglich" to RecurrenceUnit.DAY,
                "wöchentlich" to RecurrenceUnit.WEEK,
                "woechentlich" to RecurrenceUnit.WEEK,
                "monatlich" to RecurrenceUnit.MONTH,
                "jährlich" to RecurrenceUnit.YEAR,
                "jaehrlich" to RecurrenceUnit.YEAR,
            ),
            every = setOf("jeden", "jede", "jedes", "alle"),
            doubled = setOf("zwei", "zweiten", "zweite", "zweites"),
            afterCompletion = setOf(
                "nach erledigung",
                "nach der erledigung",
                "nach abschluss",
                "nach fertigstellung",
                "nach dem erledigen",
                "nachdem ich fertig bin",
                "nach erledigt",
            ),
            today = setOf("heute", "heute abend"),
            tomorrow = setOf("morgen"),
            dayAfterTomorrow = setOf("übermorgen", "uebermorgen"),
            within = setOf("in"),
            next = setOf("nächsten", "nächste", "nächstes", "naechsten", "naechste", "kommenden", "kommende"),
            week = setOf("woche"),
            last = setOf("letzten", "letzte", "letztes"),
            workday = setOf("werktag", "werktags", "arbeitstag"),
            day = setOf("tag"),
            ofTheMonth = setOf("des monats", "im monat"),
            at = setOf("um", "am", "an"),
            clock = setOf("uhr"),
            and = setOf("und"),
            on = setOf("am", "an"),
            ordinal = setOf("."),
            numbers = germanNumberWords(),
        )

        /** The default: English keywords with English weekday and month names. */
        val English: QuickAddLexicon = englishWords + namesOf(Locale.ENGLISH)

        /** German keywords on top of English, so both languages parse in a German install. */
        val German: QuickAddLexicon = English + germanWords + namesOf(Locale.GERMAN)

        private val cache = mutableMapOf<String, QuickAddLexicon>(
            Locale.ENGLISH.toLanguageTag() to English,
        )

        /**
         * The lexicon for the language the app is rendered in. Unknown languages still get their
         * weekday and month names, and English keeps working everywhere.
         */
        fun forLocale(locale: Locale): QuickAddLexicon = synchronized(cache) {
            cache.getOrPut(locale.toLanguageTag()) {
                when (locale.language) {
                    Locale.GERMAN.language -> German
                    else -> English
                } + namesOf(locale)
            }
        }

        /** Cardinal and ordinal spellings of 1…n, both reading as the same value. */
        private fun numberWords(cardinals: List<String>, ordinals: List<String>): Map<String, Int> =
            buildMap {
                cardinals.forEachIndexed { index, word -> put(word, index + 1) }
                ordinals.forEachIndexed { index, word -> put(word, index + 1) }
            }

        /**
         * German numbers decline, and the case depends on the sentence around them — "jeden
         * zweiten Montag" but "jede zweite Woche" — so each ordinal stem is listed with every
         * ending rather than guessing which one the user will type. Umlaut-free spellings are
         * included for the same reason `taeglich` is.
         */
        private fun germanNumberWords(): Map<String, Int> = buildMap {
            val cardinals = listOf(
                listOf("ein", "eine", "einen", "einem", "einer"),
                listOf("zwei"),
                listOf("drei"),
                listOf("vier"),
                listOf("fünf", "fuenf"),
                listOf("sechs"),
                listOf("sieben"),
                listOf("acht"),
                listOf("neun"),
                listOf("zehn"),
                listOf("elf"),
                listOf("zwölf", "zwoelf"),
            )
            val ordinalStems = listOf(
                listOf("erst"),
                listOf("zweit"),
                listOf("dritt"),
                listOf("viert"),
                listOf("fünft", "fuenft"),
                listOf("sechst"),
                listOf("siebt", "siebent"),
                listOf("acht"),
                listOf("neunt"),
                listOf("zehnt"),
                listOf("elft"),
                listOf("zwölft", "zwoelft"),
            )
            cardinals.forEachIndexed { index, forms -> forms.forEach { put(it, index + 1) } }
            ordinalStems.forEachIndexed { index, stems ->
                stems.forEach { stem ->
                    listOf("e", "en", "es", "er", "em").forEach { put(stem + it, index + 1) }
                }
            }
        }

        /** Weekday and month names as `java.time` spells them for [locale]. */
        private fun namesOf(locale: Locale): QuickAddLexicon {
            val styles = listOf(
                TextStyle.FULL,
                TextStyle.FULL_STANDALONE,
                TextStyle.SHORT,
                TextStyle.SHORT_STANDALONE,
            )
            val days = buildMap {
                DayOfWeek.entries.forEach { day ->
                    styles.forEach { style -> token(day.getDisplayName(style, locale))?.let { put(it, day) } }
                }
            }
            val months = buildMap {
                Month.entries.forEach { month ->
                    styles.forEach { style ->
                        token(month.getDisplayName(style, locale))?.let { put(it, month.value) }
                    }
                }
            }
            return QuickAddLexicon(dayNames = days, monthNames = months)
        }

        /**
         * Normalises a display name to a lookup key. Numeric forms (some locales abbreviate months
         * as digits) are dropped — they would swallow ordinary numbers in the typed line.
         */
        private fun token(raw: String): String? = raw.lowercase()
            .trim()
            .trimEnd('.')
            .takeIf { it.length >= 2 && it.none(Char::isDigit) }
    }
}
