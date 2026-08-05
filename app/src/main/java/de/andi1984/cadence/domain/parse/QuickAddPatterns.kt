package de.andi1984.cadence.domain.parse

/**
 * The quick-add grammar, compiled once per [QuickAddLexicon].
 *
 * Two things the patterns cannot use, because Android compiles regexes with ICU rather than the
 * JVM engine — both would pass the unit tests and then crash on a device:
 *  - inline match-mode flags beyond ICU's set (`(?u)`, `(?U)`) are rejected outright, so word
 *    boundaries are spelled out as [START]/[END] lookarounds over `\p{L}`;
 *  - `IGNORE_CASE` alone folds only ASCII on the JVM, so every non-ASCII letter in a keyword is
 *    written as a two-case character class (see [literal]) — otherwise "Übermorgen" would parse
 *    on the phone but not in a test.
 */
internal class QuickAddPatterns(lexicon: QuickAddLexicon) {

    private val unit = alt(lexicon.units.keys)
    private val dayName = alt(lexicon.dayNames.keys)
    private val every = alt(lexicon.every)
    private val next = alt(lexicon.next + lexicon.on)
    private val before = alt(lexicon.on + lexicon.at)

    /** A digit run or a spelled-out number — `3`, `three`, `dritten`. */
    private val count = """\d+|${alt(lexicon.numbers.keys)}"""

    /** "3 days after done", "2 Wochen nach Erledigung", "drei Tage nach Erledigung" */
    val afterCompletion =
        rx("""$START(?<count>$count)\s+(?<unit>$unit)\s+(?:${alt(lexicon.afterCompletion)})""")

    /**
     * "every 2nd monday", "jeden 2. Montag", "jeden letzten Freitag".
     *
     * Runs before [everyOrdinal], which would otherwise read "jeden 2." as the second day of
     * the month and leave the weekday behind in the title.
     */
    val everyNthWeekday = rx(
        """$START(?:$every)\s+(?:(?<last>${alt(lexicon.last)})|""" +
            """(?<nth>\d{1,2})(?:${alt(lexicon.ordinal)})?|(?<nthWord>${alt(lexicon.numbers.keys)}))""" +
            """\s+(?<dow>$dayName)s?(?:\s+(?:${alt(lexicon.ofTheMonth)}))?$END""",
    )

    /** "every 1st", "every 15th of the month", "jeden 15. des Monats", "jeden ersten des Monats" */
    val everyOrdinal = rx(
        """$START(?:$every)\s+(?:""" +
            """(?<dom>\d{1,2})(?:${alt(lexicon.ordinal)})(?:\s+(?:${alt(lexicon.ofTheMonth)}))?""" +
            // The spelled form must name the month, or "jeden dritten Tag" would read as a
            // day-of-month rule instead of an interval.
            """|(?<domWord>${alt(lexicon.numbers.keys)})\s+(?:${alt(lexicon.ofTheMonth)})""" +
            """)""",
    )

    /** "every last weekday", "jeden letzten Werktag" */
    val everyLast = rx(
        """$START(?:$every)\s+(?:${alt(lexicon.last)})\s+""" +
            """(?<what>${alt(lexicon.workday + lexicon.day)})$END""",
    )

    /** "every 2 weeks on thu", "alle 2 Wochen am Donnerstag", "every other day", "alle drei Tage" */
    val everyInterval = rx(
        """$START(?:$every)\s+(?:(?<doubled>${alt(lexicon.doubled)})\s+|(?<count>$count)\s+)?""" +
            """(?<unit>$unit)(?:\s+(?:${alt(lexicon.on)})\s+(?<dow>$dayName)s?)?$END""",
    )

    /** "every monday", "jeden Mo und Do" */
    val everyWeekday = rx(
        """$START(?:$every)\s+(?<days>(?:$dayName)s?""" +
            """(?:\s*(?:,|${alt(lexicon.and)})\s*(?:$dayName)s?)*)$END""",
    )

    /** Pulls the individual weekdays back out of an [everyWeekday] match. */
    val singleDayName = rx("""$START(?:$dayName)$END""")

    /** "daily", "täglich" */
    val period = rx("""$START(?<word>${alt(lexicon.periods.keys)})$END""")

    val today = rx("""$START(?:${alt(lexicon.today)})$END""")
    val dayAfterTomorrow = rx("""$START(?:${alt(lexicon.dayAfterTomorrow)})$END""")
    val tomorrow = rx("""$START(?:${alt(lexicon.tomorrow)})$END""")

    /** "in 3 days", "in 3 Tagen", "in drei Tagen" */
    val within = rx("""$START(?:${alt(lexicon.within)})\s+(?<count>$count)\s+(?<unit>$unit)$END""")

    val isoDate = rx("""$START(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})$END""")

    /** German style: "24.12." or "am 24.12.2026" */
    val numericDate =
        rx("""$START(?:(?:$before)\s+)?(?<day>\d{1,2})\.(?<month>\d{1,2})\.(?<year>\d{4})?""")

    /** "24 Dec", "am 24. Dez" */
    val dayMonth = rx(
        """$START(?:(?:$before)\s+)?(?<day>\d{1,2})\.?\s+""" +
            """(?<month>${alt(lexicon.monthNames.keys)})$END""",
    )

    /** "Dec 24" */
    val monthDay = rx("""$START(?<month>${alt(lexicon.monthNames.keys)})\s+(?<day>\d{1,2})$END""")

    /** "friday", "next friday", "am Freitag" */
    val weekdayDate =
        rx("""$START(?:(?:$next)\s+)?(?<dow>${alt(lexicon.standaloneDayNames.keys)})$END""")

    /** "next week", "nächste Woche" */
    val nextWeek = rx("""$START(?:$next)\s+(?:${alt(lexicon.week)})$END""")

    /** "17:00", "at 17:00", "um 17:00 Uhr" */
    val clockTime = rx(
        """$START(?:(?:${alt(lexicon.at)})\s+)?(?<hour>\d{1,2}):(?<minute>\d{2})""" +
            """(?:\s*(?:${alt(lexicon.clock)}))?""",
    )

    /** "um 17 Uhr" — needs the trailing word, so English never reaches it. */
    val hourClock =
        rx("""$START(?:(?:${alt(lexicon.at)})\s+)?(?<hour>\d{1,2})\s*(?:${alt(lexicon.clock)})$END""")

    /** "9am", "at 9 pm" */
    val amPm = rx("""$START(?:(?:${alt(lexicon.at)})\s+)?(?<hour>\d{1,2})\s*(?<half>am|pm)$END""")

    private companion object {

        /** `\b`, spelled out so it also fires in front of "übermorgen". */
        const val START = """(?<![\p{L}\p{N}_])"""
        const val END = """(?![\p{L}\p{N}_])"""

        fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

        /**
         * An alternation of literal words, longest first so `heute abend` wins over `heute`.
         * Whitespace inside a phrase is flexible. An empty vocabulary yields a pattern that can
         * never match, which keeps the surrounding group valid.
         */
        fun alt(words: Collection<String>): String {
            if (words.isEmpty()) return "(?!)"
            return words.sortedByDescending { it.length }.joinToString("|") { phrase(it) }
        }

        fun phrase(words: String): String = words.trim()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString("""\s+""") { literal(it) }

        /** Escapes a word, writing every non-ASCII letter as both of its cases. */
        fun literal(word: String): String = buildString {
            var index = 0
            while (index < word.length) {
                if (word[index].code < 128) {
                    val start = index
                    while (index < word.length && word[index].code < 128) index++
                    append(Regex.escape(word.substring(start, index)))
                } else {
                    val char = word[index++]
                    val lower = char.lowercaseChar()
                    val upper = char.uppercaseChar()
                    if (lower == upper) append(Regex.escape(char.toString())) else append("[$lower$upper]")
                }
            }
        }
    }
}
