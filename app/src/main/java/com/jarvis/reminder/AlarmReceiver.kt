package com.jarvis.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.routine.SmartRoutineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires scheduled timers, alarms, reminders and recurring routine jobs. Also
 * reschedules everything after reboot. After a TIMER/ALARM/REMINDER fires it is
 * removed; a ROUTINE_JOB rolls forward to its next weekday occurrence.
 */
class AlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = "AlarmReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ReminderScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return
                fire(context, id)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(tag, "Boot completed; rescheduling reminders")
                ReminderScheduler.rescheduleAll(context)
            }
        }
    }

    private fun fire(context: Context, id: String) {
        val store = ReminderStore(context)
        val items = store.load()
        val item = items.firstOrNull { it.id == id } ?: return
        Log.i(tag, "Firing ${item.kind} id=$id")

        if (item.kind == ScheduledKind.ROUTINE_JOB) {
            val engine = SmartRoutineEngine.instance
            if (engine != null) {
                val routine = engine.getRoutine(item.routineId)
                if (routine != null) {
                    scope.launch {
                        engine.executeRoutine(routine, isManual = true)
                    }
                }
            }
            val next = ReminderParser.nextWeekdayOccurrence(item.hour, item.minute, item.daysOfWeek, System.currentTimeMillis())
            val rolled = item.copy(fireAtMillis = next)
            store.update(rolled)
            ReminderScheduler.schedule(context, rolled)
            return
        }

        postNotification(context, item)
        ReminderScheduler.cancel(context, item.id)
        store.remove(item.id)
    }

    private fun postNotification(context: Context, item: ScheduledItem) {
        createChannel(context)
        val text = when (item.kind) {
            ScheduledKind.TIMER -> "Timer finished${if (item.label.isNotBlank()) ": ${item.label}" else ""}."
            ScheduledKind.ALARM -> "Alarm${if (item.label.isNotBlank()) ": ${item.label}" else ""}."
            else -> item.label.ifBlank { "Reminder." }
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = android.app.PendingIntent.getActivity(
            context, 0, launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Jarvis Reminder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + item.id.hashCode() % 1000, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_reminders"
        const val NOTIFICATION_ID = 2002
    }
}