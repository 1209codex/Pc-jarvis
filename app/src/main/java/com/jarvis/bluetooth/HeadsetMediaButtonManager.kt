package com.jarvis.bluetooth

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intercepts Bluetooth earbud and wired headset hook/media button events.
 * Enables single-click earbud tap-to-talk and physical barge-in speech interruption.
 */
class HeadsetMediaButtonManager(
    private val context: Context?,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val onHeadsetHookTriggered: () -> Unit
) {
    private val TAG = "HeadsetMediaBtnManager"

    private var mediaSession: MediaSession? = null
    private val isRunning = AtomicBoolean(false)
    private var lastTriggerTime: Long = 0L
    private val debounceWindowMs = 400L

    var isHookActivationEnabled: Boolean = true

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        val ctx = context ?: return

        try {
            mediaSession = MediaSession(ctx, "JarvisHeadsetMediaSession").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)

                val playbackState = PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(playbackState)

                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (keyEvent != null && processKeyEvent(keyEvent)) {
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })

                isActive = true
            }
            Log.i(TAG, "HeadsetMediaButtonManager active")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize MediaSession: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            Log.i(TAG, "HeadsetMediaButtonManager released")
        } catch (_: Exception) {}
    }

    /**
     * Evaluates a key event. Returns true if consumed by J.A.R.V.I.S.
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        return processKeyCode(event.keyCode, event.action)
    }

    fun processKeyCode(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN): Boolean {
        if (!isHookActivationEnabled) {
            Log.d(TAG, "Headset hook activation is disabled in settings")
            return false
        }

        if (action != KeyEvent.ACTION_DOWN) {
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                val now = timeProvider()
                if (now - lastTriggerTime > debounceWindowMs) {
                    lastTriggerTime = now
                    Log.i(TAG, "Headset hook button triggered: keyCode=$keyCode")
                    onHeadsetHookTriggered()
                    true
                } else {
                    Log.d(TAG, "Debounced headset hook event")
                    true
                }
            }
            else -> false
        }
    }
}
