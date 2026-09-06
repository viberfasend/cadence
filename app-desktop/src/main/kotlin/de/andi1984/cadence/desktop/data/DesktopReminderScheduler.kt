package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.ReminderCommand
import de.andi1984.cadence.domain.reminder.ReminderKey
import de.andi1984.cadence.domain.reminder.ReminderReconciler
import de.andi1984.cadence.ui.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.SystemTray
import java.awt.TrayIcon
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop's answer to `AlarmReminderScheduler`: there is no AlarmManager here, so a
 * coroutine polls the current task list once a minute and fires a system-tray balloon for
 * whatever just came due (ADR 0001 §8). This only fires while the app is running — a closed
 * desktop app misses reminders, same accepted trade-off the ADR names for a killed Android
 * process before this scheduler could re-arm.
 *
 * A thin adapter, like its Android counterpart: which alarm to arm, drop or leave alone is
 * [ReminderReconciler]'s answer, and this class only keeps the maps the poll reads. Its grace
 * is zero — a poll has no delivery latency to wait out — and an [ReminderCommand.Arm] naming an
 * instant already in the past is armed like any other, so the next poll fires it: the reminder
 * elapsed while the app was closed, and the balloon is still wanted.
 */
class DesktopReminderScheduler(
    private val scope: CoroutineScope,
    trayIcon: TrayIcon?,
    /**
     * Where a due reminder goes. The tray balloon by default.
     *
     * A parameter rather than a hard-wired call because a headless JVM — every CI runner, and
     * every test run — has no system tray to read a balloon back off, and "fires once and only
     * once" is the one thing about this class worth pinning down.
     */
    private val notify: (String) -> Unit = { title ->
        trayIcon?.displayMessage("Cadence", title, TrayIcon.MessageType.NONE)
    },
) : ReminderScheduler {

    /** The instant each armed alarm is set for — the reconciler's `armed` side, and what a poll
     *  reads to tell "just became due" apart from "has been due for an hour". A fired alarm stays
     *  in here at its instant: the plan keeps naming that instant, so the reconciler leaves it
     *  alone, and `fired` is what keeps the poll from firing it every thirty seconds. */
    private val armed = ConcurrentHashMap<ReminderKey, Instant>()
    private val fired = ConcurrentHashMap.newKeySet<ReminderKey>()
    private val pendingTitles = ConcurrentHashMap<ReminderKey, String>()

    init {
        scope.launch {
            while (true) {
                poll()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun sync(tasks: List<Task>, leadMinutes: List<Int>, enabled: Boolean) {
        val now = Instant.now()
        val commands = ReminderReconciler.reconcile(
            armed = armed.toMap(),
            tasks = tasks,
            leadMinutes = leadMinutes,
            enabled = enabled,
            now = now,
            grace = Duration.ZERO,
        )
        commands.forEach { command ->
            when (command) {
                is ReminderCommand.Arm -> {
                    armed[command.key] = command.at
                    // An alarm moved into the future must be able to fire again rather than
                    // staying silently suppressed by an old entry in `fired` — snoozing a task,
                    // or editing its time to something later, is the ordinary case this guards.
                    if (command.at.isAfter(now)) fired.remove(command.key)
                }
                is ReminderCommand.Cancel -> forget(command.key)
                is ReminderCommand.Keep -> Unit
            }
        }
        // The title that fires is the one from the latest reconcile, whether or not the alarm
        // itself changed. Keyed by the same set as `armed`, so nothing is left behind for an
        // alarm that is gone.
        val titleByTaskId = tasks.associate { it.id to it.title }
        armed.keys.forEach { key -> titleByTaskId[key.taskId]?.let { pendingTitles[key] = it } }
    }

    override fun cancel(taskId: String) {
        armed.keys.filter { it.taskId == taskId }.forEach(::forget)
    }

    private fun forget(key: ReminderKey) {
        armed.remove(key)
        fired.remove(key)
        pendingTitles.remove(key)
    }

    private fun poll() {
        val now = Instant.now()
        armed.forEach { (key, at) ->
            if (at.isAfter(now)) return@forEach
            // The title is looked up *before* the `fired` slot is claimed. The other order marks
            // the alarm as fired and then skips the balloon when the lookup misses, so no later
            // poll ever retries it — the reminder is lost rather than delayed.
            val title = pendingTitles[key] ?: return@forEach
            if (fired.add(key)) notify(messageFor(title, key.leadMinutes))
        }
    }

    /** Lead `0` is the legacy reminderTime alarm, and keeps its plain title exactly as it always
     *  read. A positive lead says how long is left, the same wording the Android notification's
     *  content line uses. */
    private fun messageFor(title: String, leadMinutes: Int): String =
        if (leadMinutes <= 0) title else "$title — ${leadMinutes}m"

    private companion object {
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}

/** Best-effort: several Linux desktops report [SystemTray.isSupported] `true` with no actual
 *  tray to add an icon to, so the add itself is wrapped too rather than trusted on its own. */
fun createReminderTrayIcon(): TrayIcon? {
    if (!SystemTray.isSupported()) return null
    return runCatching {
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.graphics.apply {
            color = java.awt.Color(0x3D, 0x5A, 0xFE)
            fillOval(0, 0, 16, 16)
            dispose()
        }
        val icon = TrayIcon(image, "Cadence")
        icon.isImageAutoSize = true
        SystemTray.getSystemTray().add(icon)
        icon
    }.getOrNull()
}
