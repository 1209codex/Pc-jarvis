package com.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-quality neural TTS powered by Groq PlayAI Speech API.
 * Streams/downloads neural voice synthesis and plays back through Android media framework.
 * Falls back seamlessly to [AndroidTtsEngine] if offline or on network failure.
 */
class GroqTtsEngine(
    private val context: Context,
    private var apiKeyProvider: () -> String,
    private var voiceProvider: () -> String = { "Fritz-PlayAI" },
    private val fallbackEngine: TtsEngine? = null
) : TtsEngine {

    private val TAG = "GroqTtsEngine"
    // Audio focus is managed centrally by VoiceEngine.mediaDuckingManager — no local focus here
    private val isCancelled = AtomicBoolean(false)
    private var activePlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    /** Set to true when Bluetooth SCO is active so Groq TTS routes through the BT speaker. */
    @Volatile var isBluetoothScoActive: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun start(ttsDir: File) {
        fallbackEngine?.start(ttsDir)
        Log.i(TAG, "GroqTtsEngine initialized (fallback available: ${fallbackEngine != null})")
    }

    override fun synthesize(text: String, onAudio: (ShortArray) -> Unit) {
        fallbackEngine?.synthesize(text, onAudio)
    }

    override suspend fun speak(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            Log.d(TAG, "No Groq API key provided for neural TTS — using fallback engine")
            return fallbackEngine?.speak(trimmed) ?: false
        }

        isCancelled.set(false)

        val success = try {
            speakWithGroq(trimmed, key)
        } catch (e: Exception) {
            Log.w(TAG, "Groq TTS request failed (${e.message}) — switching to fallback engine")
            false
        }

        if (!success && !isCancelled.get()) {
            return fallbackEngine?.speak(trimmed) ?: false
        }
        return success
    }

    private suspend fun speakWithGroq(text: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val voice = voiceProvider().ifBlank { "Fritz-PlayAI" }
        val payload = JSONObject().apply {
            put("model", "playai-tts")
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(200) ?: ""
            Log.w(TAG, "Groq TTS API returned HTTP ${response.code}: $errBody")
            response.close()
            return@withContext false
        }

        val bytes = response.body?.bytes()
        response.close()

        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "Groq TTS returned empty audio payload")
            return@withContext false
        }

        if (isCancelled.get()) return@withContext false

        playAudioBytes(bytes, text)
    }

    override fun updateBluetoothScoState(scoActive: Boolean) {
        isBluetoothScoActive = scoActive
        fallbackEngine?.updateBluetoothScoState(scoActive)
        Log.i(TAG, "Groq TTS Bluetooth SCO state updated: scoActive=$scoActive — TTS will route through ${if (scoActive) "Bluetooth" else "phone speaker"}")
    }

    private suspend fun playAudioBytes(bytes: ByteArray, originalText: String): Boolean = withContext(Dispatchers.IO) {
        val tempFile = try {
            File.createTempFile("jarvis_tts_", ".mp3", context.cacheDir).apply {
                writeBytes(bytes)
                deleteOnExit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp audio file for TTS playback", e)
            return@withContext false
        }

        currentTempFile = tempFile
        val deferred = CompletableDeferred<Boolean>()
        activeDeferred = deferred

        // Audio ducking is handled centrally by VoiceEngine.mediaDuckingManager

        val player = MediaPlayer().apply {
            val isBtDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                outputs?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
            } else null

            val attrs = if (isBluetoothScoActive) {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else if (isBtDevice != null) {
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
            setAudioAttributes(attrs)

            if (isBtDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    setPreferredDevice(isBtDevice)
                    Log.i(TAG, "Groq TTS MediaPlayer preferred output set to ${isBtDevice.productName} (type ${isBtDevice.type})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set preferred audio device on MediaPlayer: ${e.message}")
                }
            }

            setOnCompletionListener {
                deferred.complete(true)
            }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error during TTS playback (what=$what extra=$extra)")
                deferred.complete(false)
                true
            }
        }

        activePlayer = player

        try {
            player.setDataSource(tempFile.absolutePath)
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPlayer for Groq audio", e)
            cleanupPlayback()
            return@withContext false
        }

        val wordCount = originalText.split("\\s+".toRegex()).size
        val timeoutMs = (wordCount * 500L + 5000L).coerceAtLeast(4000L).coerceAtMost(45000L)

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false

        cleanupPlayback()
        result
    }

    private fun cleanupPlayback() {
        try {
            activePlayer?.stop()
            activePlayer?.release()
        } catch (_: Exception) {}
        activePlayer = null

        try {
            currentTempFile?.delete()
        } catch (_: Exception) {}
        currentTempFile = null

        activeDeferred = null
        // Audio focus release is handled by VoiceEngine.mediaDuckingManager.restoreVolume()
    }

    override fun stop() {
        isCancelled.set(true)
        activeDeferred?.complete(false)
        cleanupPlayback()
        fallbackEngine?.stop()
    }

    override fun cancel() {
        isCancelled.set(true)
        activeDeferred?.complete(false)
        cleanupPlayback()
        fallbackEngine?.cancel()
    }
}
