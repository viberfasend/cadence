package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.SystemTray
import java.awt.TrayIcon
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop's answer to `AlarmReminderScheduler`: there is no AlarmManager here, so a
 * coroutine polls the current task list once a minute and fires a system-tray balloon for
 * whatever just came due (ADR 0001 §8). This only fires while the app is running — a closed
 * desktop app misses reminders, same accepted trade-off the ADR names for a killed Android
 * process before this scheduler could re-arm.
 */
class DesktopReminderScheduler(
    private val scope: CoroutineScope,
    private val trayIcon: TrayIcon?,
) : ReminderScheduler {

    /** The due instant currently known for each task with a reminder, so a poll can tell "just
     *  became due" apart from "has been due for an hour" without firing every minute. */
    private val dueAt = ConcurrentHashMap<String, Instant>()
    private val fired = ConcurrentHashMap.newKeySet<String>()
    private val pendingTitles = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            while (true) {
                poll()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun sync(tasks: List<Task>) {
        val next = buildMap {
            tasks.forEach { task ->
                val due = task.dueDate
                val time = task.reminderTime
                if (due != null && time != null && !task.isDone) {
                    put(task.id, due.atTime(time).atZone(ZoneId.systemDefault()).toInstant())
                }
            }
        }
        dueAt.keys.retainAll(next.keys)
        fired.retainAll(next.keys)
        dueAt.putAll(next)
        val titles = tasks.associate { it.id to it.title }
        // A task whose reminder time changed to one already in the past must be able to fire
        // again rather than staying silently suppressed by an old entry in `fired`.
        next.forEach { (id, at) -> if (at.isAfter(Instant.now())) fired.remove(id) }
        pendingTitles.putAll(titles)
    }

    override fun cancel(taskId: String) {
        dueAt.remove(taskId)
        fired.remove(taskId)
        pendingTitles.remove(taskId)
    }

    private fun poll() {
        val now = Instant.now()
        dueAt.forEach { (taskId, at) ->
            if (!at.isAfter(now) && fired.add(taskId)) {
                notify(pendingTitles[taskId] ?: return@forEach)
            }
        }
    }

    private fun notify(title: String) {
        trayIcon?.displayMessage("Cadence", title, TrayIcon.MessageType.NONE)
    }

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
