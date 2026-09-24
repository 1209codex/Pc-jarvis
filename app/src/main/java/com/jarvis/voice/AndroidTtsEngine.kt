package com.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device TTS using Android's built-in TextToSpeech with volume boost and assistant attributes.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {
    private val TAG = "AndroidTtsEngine"
    private val audioFocus = com.jarvis.media.VoiceAudioFocusManager(context)
    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    @Volatile private var isSpeakingActive = false
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Set to true when Bluetooth SCO is active so TTS routes through the BT speaker. */
    @Volatile var isBluetoothScoActive: Boolean = false

    override fun isSpeaking(): Boolean = isSpeakingActive

    fun isHeadsetConnected(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
            } else {
                @Suppress("DEPRECATION")
                am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
            }
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun start(ttsDir: File) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)

                applyAudioAttributes()

                setupProgressListener()
                isReady = true
                Log.i(TAG, "Android TTS initialized successfully with maximum assistant gain")
            } else {
                Log.e(TAG, "Android TTS initialization failed")
            }
        }
    }

    /**
     * Applies the correct AudioAttributes based on whether Bluetooth SCO is active.
     * When SCO is active, uses VOICE_COMMUNICATION so output routes through the BT speaker.
     * Otherwise uses ASSISTANT for normal phone speaker playback.
     */
    private fun applyAudioAttributes() {
        val attrs = if (isBluetoothScoActive) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else if (isHeadsetConnected()) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }
        tts?.setAudioAttributes(attrs)
    }

    /**
     * Call when Bluetooth SCO state changes to re-route TTS output accordingly.
     */
    override fun updateBluetoothScoState(scoActive: Boolean) {
        isBluetoothScoActive = scoActive
        if (isReady) {
            applyAudioAttributes()
        }
        Log.i(TAG, "Bluetooth SCO state updated: scoActive=$scoActive — TTS will route through ${if (scoActive) "Bluetooth (SCO)" else if (isHeadsetConnected()) "Bluetooth (Media/A2DP)" else "phone speaker"}")
    }

    private fun createSpeechParams(): Bundle {
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) // 100% full gain
            if (isBluetoothScoActive) {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            } else {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeakingActive = true
                Log.d(TAG, "TTS playback started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                isSpeakingActive = false
                Log.d(TAG, "TTS playback completed: $utteranceId")
                utteranceId?.let { pendingUtterances.remove(it)?.complete(true) }
                if (pendingUtterances.isEmpty()) audioFocus.abandon()
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                isSpeakingActive = false
                Log.w(TAG, "TTS playback error: $utteranceId")
                utteranceId?.let { pendingUtterances.remove(it)?.complete(false) }
                if (pendingUtterances.isEmpty()) audioFocus.abandon()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeakingActive = false
                Log.w(TAG, "TTS playback error ($errorCode): $utteranceId")
                utteranceId?.let { pendingUtterances.remove(it)?.complete(false) }
                if (pendingUtterances.isEmpty()) audioFocus.abandon()
            }
        })
    }

    override fun synthesize(text: String, onAudio: (ShortArray) -> Unit) {
        if (!isReady || text.isBlank()) return
        applyAudioAttributes()
        val utteranceId = UUID.randomUUID().toString()
        audioFocus.requestDuckFocus()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, createSpeechParams(), utteranceId)
        onAudio(ShortArray(0))
    }

    override suspend fun speak(text: String): Boolean {
        if (!isReady || text.isBlank()) {
            if (!isReady) {
                var waited = 0
                while (!isReady && waited < 5000) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                }
                if (!isReady) return false
            } else {
                return false
            }
        }

        applyAudioAttributes()
        val utteranceId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingUtterances[utteranceId] = deferred
        audioFocus.requestDuckFocus()

        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, createSpeechParams(), utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            isSpeakingActive = false
            pendingUtterances.remove(utteranceId)
            audioFocus.abandon()
            return false
        }

        val wordCount = text.split("\\s+".toRegex()).size
        val timeoutMs = (wordCount * 500L + 5000L).coerceAtLeast(5000L).coerceAtMost(60000L)

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: run {
            isSpeakingActive = false
            pendingUtterances.remove(utteranceId)
            audioFocus.abandon()
            Log.w(TAG, "TTS speak timed out after ${timeoutMs}ms for utterance: $utteranceId")
            false
        }
    }

    override fun stop() {
        isReady = false
        isSpeakingActive = false
        pendingUtterances.values.forEach { it.complete(false) }
        pendingUtterances.clear()
        tts?.stop()
        tts?.shutdown()
        audioFocus.abandon()
        tts = null
    }

    override fun cancel() {
        isSpeakingActive = false
        pendingUtterances.values.forEach { it.complete(false) }
        pendingUtterances.clear()
        tts?.stop()
        audioFocus.abandon()
    }
}
