package com.jarvis.voice.router

import android.util.Log
import com.jarvis.voice.AsrEngine
import com.jarvis.voice.VadEngine
import com.jarvis.voice.VadEvent
import com.jarvis.voice.WakeWordEngine
import com.jarvis.voice.audio.AudioFrameConsumer
import com.jarvis.voice.audio.RollingPcmBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

enum class AudioRoutingTarget {
    WAKE_WORD_PASSIVE,
    ASR_ACTIVE_COMMAND,
    BARGE_IN_LISTENING,
    MUTED
}

/**
 * The VoiceRouter dynamically shifts the destination of continuous PCM frames
 * between the Wake Word detector, active ASR/VAD, and full-duplex Barge-In detector
 * without stopping AudioRecord.
 */
class VoiceRouter(
    val rollingBuffer: RollingPcmBuffer,
    val wakeEngine: WakeWordEngine,
    val vadEngine: VadEngine,
    var asrEngine: AsrEngine,
    val bargeInDetector: com.jarvis.voice.BargeInDetector = com.jarvis.voice.BargeInDetector(),
    var continuousAudioStream: com.jarvis.voice.audio.ContinuousAudioStream? = null
) : AudioFrameConsumer {

    private val currentTarget = AtomicReference(AudioRoutingTarget.WAKE_WORD_PASSIVE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioChannel = Channel<ShortArray>(256)
    private var audioWorkerJob: Job? = null

    init {
        startAudioWorker()
    }

    private fun startAudioWorker() {
        audioWorkerJob?.cancel()
        audioWorkerJob = scope.launch {
            for (frame in audioChannel) {
                if (currentTarget.get() == AudioRoutingTarget.ASR_ACTIVE_COMMAND && asrEngine.usesRawAudio) {
                    try {
                        asrEngine.pushAudio(frame)
                    } catch (e: Exception) {
                        // ignore cancelled / stopped errors during state shift
                    }
                }
            }
        }
    }

    var onSpeechActivity: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onBargeInDetected: ((reason: String) -> Unit)? = null

    override fun onAudioFrame(samples: ShortArray, readCount: Int) {
        when (currentTarget.get()) {
            AudioRoutingTarget.WAKE_WORD_PASSIVE -> {
                wakeEngine.acceptAudio(samples)
            }

            AudioRoutingTarget.BARGE_IN_LISTENING -> {
                // 1. Evaluate Wake Word phonemes ("Jarvis", "Hey Jarvis")
                wakeEngine.acceptAudio(samples)

                // 2. Evaluate acoustic energy burst above speaker echo threshold
                if (bargeInDetector.processFrame(samples)) {
                    onBargeInDetected?.invoke("acoustic_energy")
                }
            }

            AudioRoutingTarget.ASR_ACTIVE_COMMAND -> {
                // 1. Evaluate VAD
                val vadEvent = vadEngine.processFrame(samples)
                when (vadEvent) {
                    is VadEvent.SpeechStarted, is VadEvent.SpeechContinues -> {
                        onSpeechActivity?.invoke()
                    }
                    is VadEvent.SpeechEnded -> {
                        onSpeechEnd?.invoke()
                    }
                    is VadEvent.Silence -> Unit
                }

                // 2. Feed ASR engine strictly in FIFO order with defensive copy
                if (asrEngine.usesRawAudio) {
                    val result = audioChannel.trySend(samples.copyOf())
                    if (result.isFailure) {
                        Log.w("VoiceRouter", "Audio buffer backpressure: frame dropped during active ASR routing")
                    }
                }
            }

            AudioRoutingTarget.MUTED, null -> Unit
        }
    }

    fun routeToWakeWord() {
        continuousAudioStream?.resumeHardware()
        vadEngine.reset()
        while (audioChannel.tryReceive().isSuccess) {}
        currentTarget.set(AudioRoutingTarget.WAKE_WORD_PASSIVE)
    }

    fun routeToBargeIn() {
        continuousAudioStream?.resumeHardware()
        bargeInDetector.reset()
        while (audioChannel.tryReceive().isSuccess) {}
        currentTarget.set(AudioRoutingTarget.BARGE_IN_LISTENING)
    }

    fun routeToAsr(preRollSamples: Int = 16000 * 1): ShortArray {
        if (asrEngine.usesRawAudio) {
            continuousAudioStream?.resumeHardware()
        }
        currentTarget.set(AudioRoutingTarget.ASR_ACTIVE_COMMAND)
        while (audioChannel.tryReceive().isSuccess) {}
        val preRoll = rollingBuffer.getRecentAudio(preRollSamples)
        vadEngine.reset()
        return preRoll
    }

    fun routeToAndroidAsr() {
        mute()
        continuousAudioStream?.suspendHardware()
    }

    fun mute() {
        while (audioChannel.tryReceive().isSuccess) {}
        currentTarget.set(AudioRoutingTarget.MUTED)
    }

    fun getTarget(): AudioRoutingTarget = currentTarget.get()

    /** Cancel all pending audio-push coroutines and close audio channel. Call when shutting down the pipeline. */
    fun close() {
        audioChannel.close()
        audioWorkerJob?.cancel()
        scope.cancel()
    }
}
