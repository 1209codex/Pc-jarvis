package com.jarvis.notification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "JarvisNotificationListenerService connected and monitoring notifications")
        // Load initial active notifications
        try {
            activeNotifications?.forEach { sbn ->
                processStatusBarNotification(sbn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading initial active notifications: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { processStatusBarNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            val notifId = "${it.packageName}:${it.id}"
            NotificationStore.removeNotification(notifId)
            com.jarvis.messaging.WhatsAppMessagingEngine.instance?.removeReplyAction(notifId)
        }
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val pkg = sbn.packageName.orEmpty()
        // Ignore Jarvis's own notifications to avoid recursion
        if (pkg == packageName) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val pm = packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkg.substringAfterLast("."))

        val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

        val platform = when (pkg) {
            "com.whatsapp" -> com.jarvis.notification.WhatsAppPlatform.STANDARD
            "com.whatsapp.w4b" -> com.jarvis.notification.WhatsAppPlatform.BUSINESS
            else -> com.jarvis.notification.WhatsAppPlatform.UNKNOWN
        }

        val category = NotificationClassifier.classify(pkg, title.trim(), text.trim())
        val item = NotificationItem(
            id = "${pkg}:${sbn.id}",
            packageName = pkg,
            appName = appLabel,
            title = title.trim(),
            text = text.trim(),
            timestamp = sbn.postTime,
            isOngoing = isOngoing,
            platform = platform,
            category = category
        )

        NotificationStore.addNotification(item)
        Log.d(TAG, "Notification intercepted from $appLabel ($title): ${text.take(40)}")

        // Extract direct reply action if RemoteInput is attached
        val replyAction = notif.actions?.firstOrNull { action ->
            !action.remoteInputs.isNullOrEmpty()
        }
        if (replyAction != null && title.isNotBlank()) {
            val notifId = "${pkg}:${sbn.id}"
            com.jarvis.messaging.WhatsAppMessagingEngine.instance?.registerReplyAction(notifId, title.trim(), replyAction)
        }

        // Evaluate contextual auto-reply
        com.jarvis.messaging.WhatsAppMessagingEngine.instance?.processIncomingNotification(sbn, item)

        if (pkg.contains("whatsapp", ignoreCase = true)) {
            val platformLabel = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
            com.jarvis.controlplane.JarvisEventBus.shared.post(
                com.jarvis.controlplane.JarvisEvent.WhatsAppNotification(
                    sender = title.trim(),
                    message = text.trim(),
                    isDirectReplyAvailable = replyAction != null,
                    platform = platformLabel
                )
            )
        }
    }

    companion object {
        private const val TAG = "JarvisNotifService"

        fun isNotificationAccessGranted(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
