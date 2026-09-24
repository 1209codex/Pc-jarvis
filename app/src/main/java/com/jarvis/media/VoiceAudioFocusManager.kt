package com.jarvis.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Requests transient MAY_DUCK focus for assistant speech. This lets Android
 * keep music/video playback running while lowering it temporarily.
 */
class VoiceAudioFocusManager(context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    @Synchronized
    fun requestDuckFocus(): Boolean {
        if (hasFocus) return true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasFocus
        }

        if (focusRequest == null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                hasFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN ||
                        focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ||
                        focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }

            focusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
        }

        val request = focusRequest ?: return false
        hasFocus = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    @Synchronized
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasFocus = false
    }

    fun isFocused(): Boolean = hasFocus
}
