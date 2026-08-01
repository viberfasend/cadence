package de.andi1984.cadence.ui.format

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val dayMonthYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val weekdayShort = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

fun formatTime(time: LocalTime): String = time.format(timeOfDay)

fun formatDate(date: LocalDate): String = date.format(dayMonth)

fun formatDateWithYear(date: LocalDate): String = date.format(dayMonthYear)

fun formatWeekday(date: LocalDate): String = date.format(weekdayShort)

/** "4 days ago", "Yesterday", "Today", "Tomorrow", "Wed 13 Aug". */
fun relativeDate(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days < -1L -> "${-days} days ago"
        days in 2L..6L -> formatWeekday(date)
        date.year != today.year -> formatDateWithYear(date)
        else -> formatDate(date)
    }
}

/** The compact-density trailing label: "−4d", "Today", "Wed", "17:00". */
fun compactDate(date: LocalDate, time: LocalTime?, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "−${-days}d"
        days == 0L -> time?.let { formatTime(it) } ?: "Today"
        days in 1L..6L -> formatWeekday(date)
        else -> formatDate(date)
    }
}

/** "Tomorrow", "Thu 14 Aug" — the day headers in Upcoming. */
fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> formatDate(date)
}

fun pluralTasks(count: Int): String = if (count == 1) "1 task" else "$count tasks"

fun overdueByDays(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> "Due today"
        days == 1L -> "1 day overdue"
        else -> "$days days overdue"
    }
}
