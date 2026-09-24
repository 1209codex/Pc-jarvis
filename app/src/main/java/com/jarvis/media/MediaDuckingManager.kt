package com.jarvis.media

import android.content.Context
import android.util.Log

/**
 * Coordinates temporary audio focus for assistant speech.
 *
 * IMPORTANT:
 * Never modifies AudioManager STREAM_* volume directly. Android owns the attenuation
 * of other applications when transient MAY_DUCK focus is granted.
 */
class MediaDuckingManager(
    private val context: Context,
    private val audioFocusManager: VoiceAudioFocusManager = VoiceAudioFocusManager(context)
) {
    private val TAG = "MediaDuckingManager"

    @Volatile
    private var isDucked: Boolean = false

    @Synchronized
    fun startDucking(@Suppress("UNUSED_PARAMETER") duckPercent: Int = 45, isMusicAllowed: Boolean = true): Boolean {
        if (!isMusicAllowed) {
            restoreVolume()
            return false
        }

        if (isDucked) return true

        val granted = audioFocusManager.requestDuckFocus()
        if (granted) {
            isDucked = true
            Log.d(TAG, "Transient MAY_DUCK focus acquired; system-managed attenuation active")
        } else {
            Log.w(TAG, "Transient audio focus denied")
        }

        return granted
    }

    @Synchronized
    fun restoreVolume() {
        if (!isDucked) {
            audioFocusManager.abandon()
            return
        }

        audioFocusManager.abandon()
        isDucked = false
        Log.d(TAG, "Transient audio focus abandoned")
    }

    fun isCurrentlyDucked(): Boolean = isDucked

    companion object {
        fun calculateTargetVolume(originalVolume: Int, duckPercent: Int): Int {
            if (originalVolume <= 0) return 0
            val clampedPercent = duckPercent.coerceIn(10, 90)
            val target = Math.round(originalVolume * (clampedPercent / 100.0)).toInt()
            return target.coerceAtLeast(1)
        }
    }
}
