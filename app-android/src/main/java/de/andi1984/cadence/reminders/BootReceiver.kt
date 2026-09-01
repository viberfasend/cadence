package de.andi1984.cadence.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.andi1984.cadence.CadenceApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Alarms do not survive a reboot, so they are rebuilt from the database on boot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as? CadenceApplication)?.container ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.reminderScheduler.sync(
                    container.repository.tasks.first(),
                    container.settingsStore.state.value.reminderLeadMinutes,
                )
            } finally {
                pending.finish()
            }
        }
    }
}
