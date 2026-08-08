package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Date and time wording. Everything here is `@Composable` because both the patterns and the
 * words come from resources, so the labels follow the app language.
 */

/**
 * The language the app renders in, which is not necessarily the system default.
 *
 * Android has a per-app language picker and the shell fills this from it; the desktop has no
 * such thing, so phase 5 fills it from an explicit setting instead (ADR 0001, decision 9). Both
 * arrive here, and nothing below reads `Locale.getDefault()` on its own.
 */
val LocalAppLocale = staticCompositionLocalOf<Locale> { Locale.getDefault() }

@Composable
fun currentLocale(): Locale = LocalAppLocale.current

@Composable
private fun rememberFormatter(pattern: StringResource): DateTimeFormatter {
    val locale = currentLocale()
    val text = stringResource(pattern)
    return remember(text, locale) { DateTimeFormatter.ofPattern(text, locale) }
}

@Composable
fun formatTime(time: LocalTime): String =
    time.format(rememberFormatter(Res.string.date_pattern_time))

@Composable
fun formatDate(date: LocalDate): String =
    date.format(rememberFormatter(Res.string.date_pattern_day_month))

@Composable
fun formatDateWithYear(date: LocalDate): String =
    date.format(rememberFormatter(Res.string.date_pattern_day_month_year))

@Composable
fun formatWeekday(date: LocalDate): String =
    date.format(rememberFormatter(Res.string.date_pattern_weekday))

/** "4 days ago", "Yesterday", "Today", "Tomorrow", "Wed 13 Aug". */
@Composable
fun relativeDate(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> stringResource(Res.string.date_today)
        days == 1L -> stringResource(Res.string.date_tomorrow)
        days == -1L -> stringResource(Res.string.date_yesterday)
        days < -1L -> {
            val ago = (-days).toInt()
            pluralStringResource(Res.plurals.date_days_ago, ago, ago)
        }

        days in 2L..6L -> formatWeekday(date)
        date.year != today.year -> formatDateWithYear(date)
        else -> formatDate(date)
    }
}

/** The compact-density trailing label: "−4d", "Today", "Wed", "17:00". */
@Composable
fun compactDate(date: LocalDate, time: LocalTime?, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> stringResource(Res.string.date_compact_overdue, (-days).toInt())
        days == 0L -> time?.let { formatTime(it) } ?: stringResource(Res.string.date_today)
        days in 1L..6L -> formatWeekday(date)
        else -> formatDate(date)
    }
}

/** "Tomorrow", "Thu 14 Aug" — the day headers in Upcoming. */
@Composable
fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(Res.string.date_today)
    today.plusDays(1) -> stringResource(Res.string.date_tomorrow)
    else -> formatDate(date)
}

@Composable
fun pluralTasks(count: Int): String = pluralStringResource(Res.plurals.task_count, count, count)

@Composable
fun overdueByDays(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(date, today).toInt()
    return if (days <= 0) {
        stringResource(Res.string.date_due_today)
    } else {
        pluralStringResource(Res.plurals.date_days_overdue, days, days)
    }
}
