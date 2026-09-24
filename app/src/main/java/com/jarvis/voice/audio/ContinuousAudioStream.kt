package com.jarvis.voice.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

fun interface AudioFrameConsumer {
    fun onAudioFrame(samples: ShortArray, readCount: Int)
}

/**
 * Manages the permanent lifecycle of the 16 kHz mono AudioRecord stream.
 * It never stops between wake detection and ASR, eliminating dropped syllables,
 * audio focus races, and mic restart latency.
 */
class ContinuousAudioStream(
    private val context: Context? = null,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private val TAG = "ContinuousAudioStream"
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    val rollingBuffer = RollingPcmBuffer(capacitySamples = sampleRate * 2) // 2 sec pre-roll
    private val consumers = CopyOnWriteArrayList<AudioFrameConsumer>()

    private val isSuspended = AtomicBoolean(false)

    val minBufferSize: Int = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
        sampleRate / 10 * 2 // minimum 100ms buffer
    )

    fun addConsumer(consumer: AudioFrameConsumer) {
        consumers.add(consumer)
    }

    fun removeConsumer(consumer: AudioFrameConsumer) {
        consumers.remove(consumer)
    }

    fun clearConsumers() {
        consumers.clear()
    }

    @SuppressLint("MissingPermission")
    private fun createAndStartAudioRecord(): AudioRecord? {
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_RECOGNITION source failed, falling back to MIC")
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize * 2
                )
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                attachAudioEffects(record.audioSessionId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
                    try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val isScoOn = am?.isBluetoothScoOn == true
                        val inputDevices = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        val btInput = if (isScoOn) {
                            inputDevices?.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                            }
                        } else {
                            inputDevices?.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                            }
                        }
                        if (btInput != null) {
                            val ok = record.setPreferredDevice(btInput)
                            Log.i(TAG, "Preferred AudioRecord input device set to ${btInput.productName} (type ${btInput.type}): $ok")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed setting preferred audio input device: ${e.message}")
                    }
                }
                record.startRecording()
                return record
            }
            record.release()
        } catch (e: Exception) {
            record?.release()
            Log.e(TAG, "Error initializing AudioRecord: ${e.message}")
        }
        return null
    }

    /**
     * Dynamically updates the active AudioRecord's preferred input device (e.g. switching to/from Bluetooth).
     */
    fun updatePreferredDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val isScoOn = am?.isBluetoothScoOn == true
                val inputDevices = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val btInput = if (isScoOn) {
                    inputDevices?.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                } else {
                    inputDevices?.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                }
                synchronized(this) {
                    val currentRecord = audioRecord
                    if (currentRecord != null) {
                        val currentDev = currentRecord.preferredDevice
                        if (currentDev != btInput) {
                            val ok = currentRecord.setPreferredDevice(btInput)
                            Log.i(TAG, "Dynamic updatePreferredDevice applied (device=${btInput?.productName ?: "Built-in Mic"}): $ok")
                            if (isRunning.get() && !isSuspended.get()) {
                                try {
                                    releaseAudioEffects()
                                    currentRecord.stop()
                                    currentRecord.release()
                                } catch (_: Exception) {}
                                audioRecord = createAndStartAudioRecord()
                                Log.i(TAG, "AudioRecord refreshed for device=${btInput?.productName ?: "Built-in Mic"}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "updatePreferredDevice error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            isSuspended.set(false)
            audioRecord = createAndStartAudioRecord()
            if (audioRecord == null) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                return false
            }

            isRunning.set(true)

            workerThread = Thread({ readAudioLoop() }, "Jarvis-AudioStreamThread").apply {
                // Priority 7 gives high audio priority without starving UI thread or OS services
                priority = Thread.NORM_PRIORITY + 2
            }
            workerThread?.start()

            Log.i(TAG, "ContinuousAudioStream started successfully at ${sampleRate}Hz")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting ContinuousAudioStream", e)
            stop()
            return false
        }
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { gainControl?.release() } catch (_: Exception) {}
        gainControl = null
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching { NoiseSuppressor.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
        } catch (_: Throwable) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching { AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
        } catch (_: Throwable) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = runCatching { AutomaticGainControl.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
        } catch (_: Throwable) {}
    }

    private fun readAudioLoop() {
        // 40ms audio chunk (640 samples at 16kHz)
        val frameChunkSize = (sampleRate * 0.040).toInt()
        val readBuffer = ShortArray(frameChunkSize)

        while (isRunning.get()) {
            if (isSuspended.get()) {
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    if (!isRunning.get()) break
                }
                continue
            }

            val record = audioRecord
            if (record == null) {
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    if (!isRunning.get()) break
                }
                continue
            }
            val read = record.read(readBuffer, 0, readBuffer.size)

            if (read > 0) {
                rollingBuffer.write(readBuffer, 0, read)

                // Dispatch snapshot to active registered consumers
                val frame = readBuffer.copyOf(read)
                for (consumer in consumers) {
                    try {
                        consumer.onAudioFrame(frame, read)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in audio consumer: ${e.message}")
                    }
                }
            } else if (read < 0) {
                if (isSuspended.get() || !isRunning.get()) continue
                Log.w(TAG, "AudioRecord read error code: $read")
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    if (!isRunning.get()) break
                    Log.i(TAG, "Attempting AudioRecord hardware recovery...")
                    var recovered = false
                    for (attempt in 1..5) {
                        try {
                            Thread.sleep((attempt * 250L).coerceAtMost(1500L))
                            if (!isRunning.get() || isSuspended.get()) break
                            synchronized(this) {
                                try {
                                    audioRecord?.stop()
                                    audioRecord?.release()
                                } catch (_: Exception) {}
                                audioRecord = createAndStartAudioRecord()
                                if (audioRecord != null) {
                                    recovered = true
                                    Log.i(TAG, "AudioRecord successfully recovered on attempt $attempt")
                                }
                            }
                            if (recovered) break
                        } catch (e: Exception) {
                            Log.w(TAG, "Recovery attempt $attempt failed: ${e.message}")
                        }
                    }

                    if (!recovered && !isSuspended.get()) {
                        Log.e(TAG, "All AudioRecord recovery attempts failed; terminating loop")
                        break
                    }
                }
            }
        }
    }

    /**
     * Temporarily releases the AudioRecord hardware (mic) without stopping the capture thread.
     * Use before handing the mic to AndroidSpeechRecognizer. Call [resumeHardware] to reclaim.
     */
    @Synchronized
    fun suspendHardware() {
        if (!isRunning.get() || isSuspended.get()) return
        isSuspended.set(true)
        try {
            noiseSuppressor?.release(); noiseSuppressor = null
            echoCanceler?.release(); echoCanceler = null
            gainControl?.release(); gainControl = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "suspendHardware: error releasing AudioRecord: ${e.message}")
        }
        Log.i(TAG, "ContinuousAudioStream hardware suspended (mic released)")
    }

    /**
     * Reclaims the AudioRecord hardware after [suspendHardware].
     */
    @Synchronized
    fun resumeHardware(): Boolean {
        if (!isRunning.get() || !isSuspended.get()) return true
        val record = createAndStartAudioRecord()
        audioRecord = record
        if (record != null) {
            isSuspended.set(false)
            Log.i(TAG, "ContinuousAudioStream hardware resumed successfully")
            return true
        } else {
            Log.e(TAG, "ContinuousAudioStream hardware resume failed: AudioRecord could not be initialized")
            return false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get()) return  // idempotent — prevents double-stop IllegalStateException (BUG-031)
        isRunning.set(false)
        isSuspended.set(false)
        workerThread?.interrupt()
        workerThread = null

        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            gainControl?.release()
            noiseSuppressor = null
            echoCanceler = null
            gainControl = null

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio hardware: ${e.message}")
        }

        Log.i(TAG, "ContinuousAudioStream stopped and hardware released")
    }

    fun isRecording(): Boolean = isRunning.get() && !isSuspended.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun isHealthy(): Boolean = isRunning.get() && workerThread?.isAlive == true && (isSuspended.get() || audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
}
