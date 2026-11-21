package com.jaiden.steady

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        when (intent.action) {
            Reminders.ACTION_FIRE -> { Reminders.onFire(context, intent); Widgets.updateAll(context) }
            Reminders.ACTION_SNOOZE -> Reminders.onSnooze(context, intent)
            Steps.ACTION_SAMPLE -> {
                val pending = goAsync()
                Steps.sample(context) {
                    Steps.schedule(context)
                    pending.finish()
                }
            }
        }
    }
}

/** Alarms are wiped when the phone restarts or the app updates, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        Reminders.rescheduleAll(context)
        Steps.schedule(context)
        Steps.sample(context, null)
        Widgets.updateAll(context)
    }
}
