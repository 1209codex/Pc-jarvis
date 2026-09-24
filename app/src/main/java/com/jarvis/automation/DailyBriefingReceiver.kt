package com.jarvis.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.service.JarvisForegroundService
import com.jarvis.tools.DailyBriefingTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DailyBriefingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyBriefingReceiver"
        private const val CHANNEL_ID = "jarvis_briefing_channel"
        private const val NOTIFICATION_ID = 3001
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "DailyBriefingReceiver triggered with action: ${intent?.action}")

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val store = com.jarvis.ui.data.UiPreferencesStore(context)
                val settings = store.loadSettings()
                if (!settings.dailyBriefingEnabled) {
                    Log.i(TAG, "Daily briefing is disabled in settings; skipping broadcast")
                    return@launch
                }

                // 1. Generate the executive briefing
                val tool = DailyBriefingTool(context)
                val result = tool.execute(emptyMap())
                val briefingText = result.message

                // 2. If Jarvis voice service is active, speak the briefing aloud
                JarvisForegroundService.instance?.voiceEngine?.ttsEngine?.speak(briefingText)

                // 3. Post notification to status bar
                showBriefingNotification(context, briefingText)

                // 4. Re-schedule for tomorrow at user's configured time
                val (hour, minute) = try {
                    val parts = settings.dailyBriefingTime.split(":")
                    Pair(parts[0].toInt().coerceIn(0, 23), parts[1].toInt().coerceIn(0, 59))
                } catch (_: Exception) {
                    Pair(8, 0)
                }
                DailyBriefingScheduler.scheduleNextBriefing(context, hour = hour, minute = minute)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing daily briefing receiver: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showBriefingNotification(context: Context, briefingText: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Briefings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily proactive morning briefings from J.A.R.V.I.S."
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("🌅 J.A.R.V.I.S. Morning Briefing")
            .setContentText(briefingText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefingText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
