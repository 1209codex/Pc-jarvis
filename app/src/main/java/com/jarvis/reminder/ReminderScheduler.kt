package com.jarvis.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Thin AlarmManager scheduling glue. All parsing/persistence lives elsewhere;
 * this only maps a ScheduledItem to a PendingIntent broadcast for AlarmReceiver.
 * Uses exact alarms when the caller is allowed (API 31+ SCHEDULE_EXACT_ALARM),
 * otherwise falls back to inexact so honesty about timing is preserved.
 */
object ReminderScheduler {

    const val ACTION_FIRE = "com.jarvis.action.ALARM_FIRE"
    const val EXTRA_ID = "reminder_id"

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, item: ScheduledItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, item.id)
        val triggerAt = item.fireAtMillis.coerceAtLeast(System.currentTimeMillis())
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, id))
    }

    fun rescheduleAll(context: Context) {
        val items = ReminderStore(context).load()
        for (item in items) schedule(context, item)
    }
}