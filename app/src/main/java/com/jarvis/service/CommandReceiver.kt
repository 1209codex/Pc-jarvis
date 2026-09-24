package com.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Direct Command Receiver for ADB automation, desktop bridge, and external integrations.
 */
class CommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CommandReceiver"
        const val ACTION_EXECUTE_COMMAND = "com.jarvis.action.EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "command"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_EXECUTE_COMMAND || action == "com.jarvis.intent.ACTION_EXECUTE_COMMAND") {
            val command = intent.getStringExtra(EXTRA_COMMAND)
                ?: intent.getStringExtra("query")
                ?: intent.getStringExtra("command_text")
                ?: return

            Log.i(TAG, "CommandReceiver received command: '$command'")
            val service = JarvisForegroundService.instance
            if (service != null) {
                service.executeTextCommand(command)
            } else {
                val request = JarvisForegroundService.commandStartRequest(command) ?: return
                val startIntent = Intent(context, JarvisForegroundService::class.java).apply {
                    this.action = request.action
                    putExtra(EXTRA_COMMAND, request.command)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }
        }
    }
}
