package com.jarvis.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.jarvis.overlay.JarvisFloatingOverlayService
import com.jarvis.service.JarvisForegroundService

/**
 * Trampoline activity that handles system [Intent.ACTION_ASSIST] and [Intent.ACTION_VOICE_ASSIST]
 * intents dispatched by hardware assist buttons, gestures, or third-party launchers.
 */
class JarvisAssistActivity : Activity() {

    companion object {
        private const val TAG = "JarvisAssistActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "JarvisAssistActivity triggered with action: ${intent?.action}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        try {
            // 1. Ensure Jarvis Foreground Service is running
            val serviceIntent = Intent(this, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 2. Open floating overlay or trigger instant manual voice listening
            val overlayIntent = Intent(this, JarvisFloatingOverlayService::class.java).apply {
                action = JarvisFloatingOverlayService.ACTION_TALK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent)
            } else {
                startService(overlayIntent)
            }

            // Trigger instant manual listening
            JarvisForegroundService.instance?.voiceEngine?.startManualListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Assist intent: ${e.message}", e)
        }

        // Finish immediately so the underlying app stays active behind the assistant overlay
        finish()
    }
}
