package de.andi1984.cadence.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Redraws the task widgets just after midnight, so Today turns over without anyone writing a
 * task or opening the app.
 *
 * Every other widget redraw is caused by a write — the container's collector while the process
 * lives, [ToggleTaskCallback] for a tap — but the Today list changes at midnight with nothing
 * written at all, and until something did, the widget kept showing yesterday for up to the
 * `updatePeriodMillis` half hour. The Android screen learnt the same lesson (#115); this is the
 * widget's version of its midnight `LaunchedEffect`, with an alarm in place of a coroutine
 * because there is no process to hold one.
 *
 * Inexact and windowed, like the reminders: a minute's slack is nothing at midnight and buys
 * freedom from the exact-alarm permission. The alarm is (re)armed from every task-list
 * `provideGlance` — which runs at every session start, boot and APK install included, because the
 * system sends the provider an update then — and the redraw this receiver asks for is itself a
 * session start, so the chain re-arms from inside the widget it woke, for as long as one exists.
 * An alarm with no widget left to draw fires once more and arms nothing: `updateAll` on a widget
 * with no instance composes nothing, and [schedule] is only called from a composition.
 */
class WidgetMidnightRefresh : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetUpdater.refreshAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "de.andi1984.cadence.widget.MIDNIGHT_REFRESH"
        private const val REQUEST_CODE = 0x4D49_444E // "MIDN"
        private const val WINDOW_MILLIS = 60_000L

        /** Arms the next midnight's refresh; calling it again only moves the same alarm. */
        fun schedule(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val zone = ZoneId.systemDefault()
            // A second past midnight, not on it: `LocalDate.now()` a hair early would return the
            // day that is ending, and the widget would redraw yesterday and wait another day.
            val triggerAt = LocalDate.now(zone).plusDays(1).atStartOfDay(zone)
                .plusSeconds(1).toInstant().toEpochMilli()
            val intent = Intent(context, WidgetMidnightRefresh::class.java).setAction(ACTION)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setWindow(AlarmManager.RTC, triggerAt, WINDOW_MILLIS, pendingIntent)
        }
    }
}
