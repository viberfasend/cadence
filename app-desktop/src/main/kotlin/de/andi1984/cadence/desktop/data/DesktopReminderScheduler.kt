package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.ReminderPlanner
import de.andi1984.cadence.ui.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.SystemTray
import java.awt.TrayIcon
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Identifies one alarm: a task and how long before its due time this one is, `0` for the moment
 *  [Task.reminderTime] itself names. A task can now have several alarms in flight at once, one
 *  per configured lead, so the bare task id the maps below used to key on is no longer enough. */
private data class ReminderKey(val taskId: String, val leadMinutes: Int)

/**
 * The desktop's answer to `AlarmReminderScheduler`: there is no AlarmManager here, so a
 * coroutine polls the current task list once a minute and fires a system-tray balloon for
 * whatever just came due (ADR 0001 §8). This only fires while the app is running — a closed
 * desktop app misses reminders, same accepted trade-off the ADR names for a killed Android
 * process before this scheduler could re-arm.
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

    /** The due instant currently known for each armed alarm, so a poll can tell "just became
     *  due" apart from "has been due for an hour" without firing every minute. */
    private val dueAt = ConcurrentHashMap<ReminderKey, Instant>()
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

    override fun sync(tasks: List<Task>, leadMinutes: List<Int>) {
        val planned = ReminderPlanner.plan(tasks, leadMinutes)
        val next = planned.associate { ReminderKey(it.taskId, it.leadMinutes) to it.triggerAt }
        dueAt.keys.retainAll(next.keys)
        fired.retainAll(next.keys)
        // All three maps are keyed by the same set of alarms, so all three are pruned to it.
        // Leaving titles behind kept one entry per alarm ever seen for the life of the process.
        pendingTitles.keys.retainAll(next.keys)
        dueAt.putAll(next)
        // An alarm whose trigger moved to one already in the past must be able to fire again
        // rather than staying silently suppressed by an old entry in `fired` — editing a task's
        // due time or reminder time to something earlier is the ordinary case this guards.
        next.forEach { (key, at) -> if (at.isAfter(Instant.now())) fired.remove(key) }
        val titleByTaskId = tasks.associate { it.id to it.title }
        pendingTitles.putAll(next.keys.mapNotNull { key -> titleByTaskId[key.taskId]?.let { key to it } })
    }

    override fun cancel(taskId: String) {
        val keys = dueAt.keys.filter { it.taskId == taskId }
        keys.forEach { key ->
            dueAt.remove(key)
            fired.remove(key)
            pendingTitles.remove(key)
        }
    }

    private fun poll() {
        val now = Instant.now()
        dueAt.forEach { (key, at) ->
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
