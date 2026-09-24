package com.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles boot and package-update broadcasts to re-arm persistent alarms (e.g. daily briefing).
 *
 * NOTE: This receiver does NOT start the microphone foreground service on boot.
 * Android 14+ (API 34+) restricts microphone FGS startup from background/boot state.
 * The FGS is started from user interaction in MainActivity instead.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "JarvisBootReceiver received action: $action")

        if (action == "com.jarvis.action.HEARTBEAT") {
            Log.d(TAG, "Jarvis heartbeat watchdog pulse — active service=${JarvisForegroundService.instance != null}")
            return
        }

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_QUICKBOOT_POWERON ||
            action == ACTION_HTC_QUICKBOOT) {

            // IMPORTANT: Do NOT start a microphone foreground service directly from BOOT_COMPLETED.
            // Android 14+ imposes while-in-use restrictions on microphone FGS, and Android 15
            // specifically blocks microphone FGS startup from background state (including boot).
            // The correct architecture is: user opens app → user triggers listening → FGS starts.
            // Attempting to startForegroundService() from boot will throw a ForegroundServiceStartNotAllowedException
            // on modern devices, silently fail, or cause an ANR.
            //
            // The service is started from MainActivity / user interaction instead.
            Log.i(TAG, "Boot completed — skipping direct FGS start (Android 14+ mic FGS restriction). " +
                "Service will start when user opens the app.")

            // Re-arm the daily morning briefing alarm (safe — no FGS needed)
            try {
                com.jarvis.automation.DailyBriefingScheduler.scheduleNextBriefing(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-arm daily briefing on boot: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "JarvisBootReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        fun isSupportedAction(action: String?): Boolean {
            return action == Intent.ACTION_BOOT_COMPLETED ||
                   action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                   action == ACTION_QUICKBOOT_POWERON ||
                   action == ACTION_HTC_QUICKBOOT
        }
    }
}
