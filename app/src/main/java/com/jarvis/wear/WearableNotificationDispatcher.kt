package com.jarvis.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Dispatches watch-optimized notification cards with WearableExtender actions.
 * Enables smartwatch users to view assistant telemetry and trigger one-tap actions
 * directly from their wrist.
 */
object WearableNotificationDispatcher {
    const val CHANNEL_ID = "jarvis_wearable_companion"
    private const val CHANNEL_NAME = "J.A.R.V.I.S. Wearable Companion"
    const val NOTIFICATION_ID = 2026

    fun dispatchWearableCard(
        context: Context,
        title: String = "J.A.R.V.I.S. Smartwatch Companion",
        statusText: String = "Ready • Tap action to trigger"
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Companion status and remote actions for smartwatches and wearables"
            }
            manager.createNotificationChannel(channel)
        }

        // Action 1: Voice Wake
        val wakeIntent = Intent(context, WearableCompanionReceiver::class.java).apply {
            action = WearableCompanionReceiver.ACTION_WEAR_WAKE
        }
        val wakePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: Stop / Cancel
        val stopIntent = Intent(context, WearableCompanionReceiver::class.java).apply {
            action = WearableCompanionReceiver.ACTION_WEAR_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: Driving Mode Quick Preset
        val driveIntent = Intent(context, WearableCompanionReceiver::class.java).apply {
            action = WearableCompanionReceiver.ACTION_WEAR_COMMAND
            putExtra(WearableCompanionReceiver.EXTRA_COMMAND_TEXT, "start driving mode")
        }
        val drivePendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            driveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build WearableExtender
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_btn_speak_now, "🎙 Talk", wakePendingIntent).build())
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "🛑 Stop", stopPendingIntent).build())
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_dialog_map, "🚗 Drive", drivePendingIntent).build())

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "🎙 Talk", wakePendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "🛑 Stop", stopPendingIntent)
            .extend(wearableExtender)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID)
    }
}
