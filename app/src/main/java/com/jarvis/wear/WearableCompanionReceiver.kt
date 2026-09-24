package com.jarvis.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.service.JarvisForegroundService
import com.jarvis.service.ServiceStartRequest
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.ui.model.AppSettings

/**
 * BroadcastReceiver for Smartwatch, WearOS, and companion bridge triggers.
 * Handles remote voice wake, direct command execution, and task cancellations.
 */
class WearableCompanionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "WearableCompanionReceiver"
        const val ACTION_WEAR_WAKE = "com.jarvis.action.WEAR_WAKE"
        const val ACTION_WEAR_COMMAND = "com.jarvis.action.WEAR_COMMAND"
        const val ACTION_WEAR_STOP = "com.jarvis.action.WEAR_STOP"
        const val EXTRA_COMMAND_TEXT = "command_text"

        fun parseCommand(action: String?, commandExtra: String?): String? {
            if (action != ACTION_WEAR_COMMAND) return null
            return commandExtra?.trim()?.ifBlank { null }
        }

        fun parseCommandFromIntent(intent: Intent?): String? {
            if (intent?.action != ACTION_WEAR_COMMAND) return null
            return intent.getStringExtra(EXTRA_COMMAND_TEXT)?.trim()?.ifBlank { null }
        }

        fun startRequestForBroadcast(action: String?, command: String?): ServiceStartRequest? = when (action) {
            ACTION_WEAR_WAKE -> ServiceStartRequest(JarvisForegroundService.ACTION_MANUAL_LISTEN)
            ACTION_WEAR_COMMAND -> JarvisForegroundService.commandStartRequest(command)
            else -> null
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received wearable broadcast action: $action")

        val settings = runCatching { context?.let { UiPreferencesStore(it).loadSettings() } }.getOrNull() ?: AppSettings()
        if (!settings.wearableSyncEnabled) {
            Log.d(TAG, "Wearable synchronization is disabled in settings")
            return
        }

        val service = JarvisForegroundService.instance
        if (service == null) {
            val request = startRequestForBroadcast(action, intent.getStringExtra(EXTRA_COMMAND_TEXT)) ?: return
            Log.w(TAG, "JarvisForegroundService not running; starting it with action=${request.action}")
            val startIntent = Intent(context, JarvisForegroundService::class.java).apply {
                this.action = request.action
                request.command?.let { putExtra("command", it) }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context?.startForegroundService(startIntent)
            } else {
                context?.startService(startIntent)
            }
            return
        }

        when (action) {
            ACTION_WEAR_WAKE -> {
                Log.i(TAG, "Triggering voice listening from wearable action")
                service.voiceEngine?.startManualListening()
            }
            ACTION_WEAR_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND_TEXT)?.trim()
                if (!command.isNullOrBlank()) {
                    Log.i(TAG, "Executing wearable command: '$command'")
                    service.executeTextCommand(command)
                } else {
                    Log.w(TAG, "Received empty wearable command text")
                }
            }
            ACTION_WEAR_STOP -> {
                Log.i(TAG, "Stopping active execution from wearable action")
                service.voiceEngine?.cancelCurrentTask()
            }
        }
    }
}
