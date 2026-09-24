package com.jarvis.assistant

import android.content.Intent
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.jarvis.service.JarvisForegroundService

/**
 * Main Android system entry point for registering Jarvis as the Default Digital Assistant.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "JarvisVoiceInteractSvc"
        var isServiceActive: Boolean = false
            private set
    }

    override fun onReady() {
        super.onReady()
        isServiceActive = true
        Log.i(TAG, "JarvisVoiceInteractionService is onReady (System Default Assistant active)")

        // Auto-warm the Jarvis background service so wake word and ASR are hot
        try {
            val serviceIntent = Intent(this, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start JarvisForegroundService from VoiceInteractionService: ${e.message}")
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        isServiceActive = false
        Log.i(TAG, "JarvisVoiceInteractionService onShutdown")
    }
}
