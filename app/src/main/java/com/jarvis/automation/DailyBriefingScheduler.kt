package com.jarvis.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object DailyBriefingScheduler {
    private const val TAG = "DailyBriefingScheduler"
    const val REQUEST_CODE = 2001
    const val ACTION_DAILY_BRIEFING = "com.jarvis.action.DAILY_BRIEFING"

    /**
     * Pure function to calculate the next trigger timestamp for a given daily hour & minute.
     * If the target time today has already passed, schedules for tomorrow.
     */
    fun calculateNextTriggerMillis(hour: Int = 8, minute: Int = 0, nowMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (cal.timeInMillis <= nowMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Schedules the next daily morning briefing alarm with AlarmManager.
     */
    fun scheduleNextBriefing(context: Context, hour: Int = 8, minute: Int = 0) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailyBriefingReceiver::class.java).apply {
                action = ACTION_DAILY_BRIEFING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerMillis = calculateNextTriggerMillis(hour, minute)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
            Log.i(TAG, "Scheduled next daily briefing for timestamp: $triggerMillis")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule daily briefing alarm: ${e.message}", e)
        }
    }

    /**
     * Cancels any scheduled daily briefing alarm.
     */
    fun cancelBriefing(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailyBriefingReceiver::class.java).apply {
                action = ACTION_DAILY_BRIEFING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.i(TAG, "Cancelled scheduled daily briefing alarm")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel daily briefing alarm: ${e.message}", e)
        }
    }
}
