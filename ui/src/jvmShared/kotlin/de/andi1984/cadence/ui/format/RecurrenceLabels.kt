package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.recurrence.MonthlyPhrase
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import de.andi1984.cadence.domain.recurrence.RecurrenceSummary
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Puts words to a [RecurrenceSummary]. The engine works out what a rule means; this file is the
 * only place that decides how it reads, so a translation only has to touch `strings.xml`.
 */

/** "day"/"days" — also used by the interval dropdown in the repeat editor. */
@Composable
fun unitWord(unit: RecurrenceUnit, count: Int): String = pluralStringResource(
    when (unit) {
        RecurrenceUnit.DAY -> Res.plurals.unit_day
        RecurrenceUnit.WEEK -> Res.plurals.unit_week
        RecurrenceUnit.MONTH -> Res.plurals.unit_month
        RecurrenceUnit.YEAR -> Res.plurals.unit_year
    },
    count,
)

/** "1st" in English, "1." in German. */
@Composable
fun ordinal(value: Int): String {
    val suffix = stringResource(
        when {
            value % 100 in 11..13 -> Res.string.ordinal_suffix_th
            value % 10 == 1 -> Res.string.ordinal_suffix_st
            value % 10 == 2 -> Res.string.ordinal_suffix_nd
            value % 10 == 3 -> Res.string.ordinal_suffix_rd
            else -> Res.string.ordinal_suffix_th
        },
    )
    return stringResource(Res.string.ordinal_format, value, suffix)
}

/** The short weekday name in the app language, e.g. "Thu" or "Do.". */
@Composable
fun weekdayShort(day: DayOfWeek): String =
    day.getDisplayName(TextStyle.SHORT, currentLocale())

/** "Every 2 weeks on Thu", "Monthly on the 1st", "3 days after done". */
@Composable
fun describeRecurrence(rule: RecurrenceRule): String =
    describeRecurrence(RecurrenceEngine.summarize(rule))

@Composable
fun describeRecurrence(summary: RecurrenceSummary): String = when (summary) {
    is RecurrenceSummary.AfterCompletion -> stringResource(
        Res.string.recurrence_after_done,
        summary.interval,
        unitWord(summary.unit, summary.interval),
    )

    is RecurrenceSummary.Schedule -> {
        val base = if (summary.interval == 1) {
            stringResource(
                when (summary.unit) {
                    RecurrenceUnit.DAY -> Res.string.recurrence_daily
                    RecurrenceUnit.WEEK -> Res.string.recurrence_weekly
                    RecurrenceUnit.MONTH -> Res.string.recurrence_monthly
                    RecurrenceUnit.YEAR -> Res.string.recurrence_yearly
                },
            )
        } else {
            stringResource(
                Res.string.recurrence_every_n,
                summary.interval,
                unitWord(summary.unit, summary.interval),
            )
        }
        val qualifier = scheduleQualifier(summary)
        if (qualifier == null) {
            base
        } else {
            stringResource(Res.string.recurrence_with_qualifier, base, qualifier)
        }
    }
}

/**
 * The same summary as a mid-sentence fragment ("… · repeats every 2 weeks"). Only the first
 * character is lowercased, which is right in English and leaves German nouns alone.
 */
@Composable
fun describeRecurrenceInline(rule: RecurrenceRule): String {
    val locale = currentLocale()
    return describeRecurrence(rule).replaceFirstChar { it.lowercase(locale) }
}

@Composable
private fun scheduleQualifier(summary: RecurrenceSummary.Schedule): String? {
    val monthly = summary.monthly
    return when {
        summary.daysOfWeek.isNotEmpty() -> {
            // joinToString is not inline, so the locale is resolved before the lambda runs.
            val locale = currentLocale()
            val days = summary.daysOfWeek.joinToString(", ") {
                it.getDisplayName(TextStyle.SHORT, locale)
            }
            stringResource(Res.string.recurrence_on_days, days)
        }

        monthly != null -> monthlyPhraseText(monthly)

        else -> null
    }
}

@Composable
private fun monthlyPhraseText(phrase: MonthlyPhrase): String = when (phrase) {
    is MonthlyPhrase.DayOfMonth ->
        stringResource(Res.string.recurrence_on_day_of_month, ordinal(phrase.day))

    MonthlyPhrase.LastDay -> stringResource(Res.string.recurrence_on_last_day)

    MonthlyPhrase.LastWeekday -> stringResource(Res.string.recurrence_on_last_weekday)

    is MonthlyPhrase.NthWeekday -> {
        val name = phrase.day.getDisplayName(TextStyle.FULL, currentLocale())
        if (phrase.nth >= 5) {
            stringResource(Res.string.recurrence_on_last_named_weekday, name)
        } else {
            stringResource(Res.string.recurrence_on_nth_weekday, ordinal(phrase.nth), name)
        }
    }
}
