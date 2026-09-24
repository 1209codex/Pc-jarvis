package com.jarvis.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Dedicated single-utterance audio recorder for wake-word enrollment and calibration.
 *
 * Records 16 kHz Mono 16-bit PCM audio frames, computes live normalized RMS energy levels
 * for real-time visual feedback, and cleans up audio hardware handles safely.
 */
class VoiceSampleRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val customAudioSource: ((buffer: ShortArray) -> Int)? = null
) {
    private val TAG = "VoiceSampleRecorder"
    private val isRecording = AtomicBoolean(false)

    /**
     * Records audio for [durationMs] (default 2000ms), periodically reporting [onRmsLevel] (0.0 .. 1.0)
     * for visual level bar animation.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordSample(
        durationMs: Long = 2000L,
        onRmsLevel: ((Float) -> Unit)? = null
    ): ShortArray? = withContext(Dispatchers.IO) {
        if (customAudioSource != null) {
            return@withContext recordFromCustomSource(durationMs, onRmsLevel)
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioRecord minBufferSize invalid: $minBufferSize")
            return@withContext null
        }

        val bufferSize = maxOf(minBufferSize, 3200)
        var audioRecord: AudioRecord? = null

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_RECOGNITION initialization failed, attempting standard MIC")
                audioRecord.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2
                )
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord.release()
                return@withContext null
            }

            audioRecord.startRecording()
            isRecording.set(true)

            val totalSamplesTarget = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
            val captured = ArrayList<Short>(totalSamplesTarget)
            val chunk = ShortArray(640) // 40ms frames at 16kHz

            while (isActive && isRecording.get() && captured.size < totalSamplesTarget) {
                val toRead = minOf(chunk.size, totalSamplesTarget - captured.size)
                val read = audioRecord.read(chunk, 0, toRead)
                if (read > 0) {
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val sample = chunk[i]
                        captured.add(sample)
                        sumSq += sample.toDouble() * sample.toDouble()
                    }
                    val rms = sqrt(sumSq / read)
                    // Normalize RMS to [0.0 .. 1.0] range for level meter UI
                    val normalized = (rms / 32768.0 * 8.0).toFloat().coerceIn(0.0f, 1.0f)
                    onRmsLevel?.invoke(normalized)
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error code: $read")
                    break
                }
            }

            val result = ShortArray(captured.size)
            for (i in captured.indices) {
                result[i] = captured[i]
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception during voice sample recording", e)
            null
        } finally {
            isRecording.set(false)
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Exception releasing AudioRecord", e)
            }
        }
    }

    private fun recordFromCustomSource(
        durationMs: Long,
        onRmsLevel: ((Float) -> Unit)?
    ): ShortArray {
        isRecording.set(true)
        val totalSamplesTarget = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val captured = ArrayList<Short>(totalSamplesTarget)
        val chunk = ShortArray(640)

        while (isRecording.get() && captured.size < totalSamplesTarget) {
            val toRead = minOf(chunk.size, totalSamplesTarget - captured.size)
            val read = customAudioSource?.invoke(chunk) ?: 0
            if (read > 0) {
                var sumSq = 0.0
                val actualRead = minOf(read, toRead)
                for (i in 0 until actualRead) {
                    captured.add(chunk[i])
                    sumSq += chunk[i].toDouble() * chunk[i].toDouble()
                }
                val rms = sqrt(sumSq / actualRead)
                val normalized = (rms / 32768.0 * 8.0).toFloat().coerceIn(0.0f, 1.0f)
                onRmsLevel?.invoke(normalized)
            } else {
                break
            }
        }
        isRecording.set(false)

        val result = ShortArray(captured.size)
        for (i in captured.indices) {
            result[i] = captured[i]
        }
        return result
    }

    fun stop() {
        isRecording.set(false)
    }

    fun isRecording(): Boolean = isRecording.get()
}
