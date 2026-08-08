package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.Instant

/**
 * The two labels the About and sync sections need that Android used to hand over ready-made —
 * `Formatter.formatShortFileSize` and `DateUtils.getRelativeTimeSpanString`. Neither has a
 * desktop counterpart, and both produce prose, so they belong here with the rest of the wording.
 */

/** "812 B", "1,4 kB", "12,0 MB" — decimal units, the way a file manager shows them. */
@Composable
fun formatFileSize(bytes: Long): String {
    val locale = currentLocale()
    return when {
        bytes < 1_000L -> stringResource(Res.string.size_bytes, bytes.toString())
        bytes < 1_000_000L ->
            stringResource(Res.string.size_kilobytes, oneDecimal(bytes / 1_000.0, locale))

        else -> stringResource(Res.string.size_megabytes, oneDecimal(bytes / 1_000_000.0, locale))
    }
}

private fun oneDecimal(value: Double, locale: java.util.Locale): String =
    String.format(locale, "%.1f", value)

/** "just now", "4 minutes ago", "3 hours ago", "2 days ago" — coarse on purpose; this only ever
 *  answers "is the synced file current?". */
@Composable
fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(instant, now)
    val minutes = elapsed.toMinutes()
    val hours = elapsed.toHours()
    val days = elapsed.toDays()
    return when {
        minutes < 1L -> stringResource(Res.string.time_just_now)
        hours < 1L -> pluralStringResource(Res.plurals.time_minutes_ago, minutes.toInt(), minutes.toInt())
        days < 1L -> pluralStringResource(Res.plurals.time_hours_ago, hours.toInt(), hours.toInt())
        else -> pluralStringResource(Res.plurals.date_days_ago, days.toInt(), days.toInt())
    }
}
