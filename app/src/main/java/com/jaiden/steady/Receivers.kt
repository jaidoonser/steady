package com.jaiden.steady

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        when (intent.action) {
            Reminders.ACTION_FIRE -> Reminders.onFire(context, intent)
            Reminders.ACTION_SNOOZE -> Reminders.onSnooze(context, intent)
        }
    }
}

/** Alarms are wiped when the phone restarts or the app updates, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.ensureChannel(context)
        Reminders.rescheduleAll(context)
    }
}
