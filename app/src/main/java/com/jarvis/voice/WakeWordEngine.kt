package com.jarvis.voice

import android.content.Context
import android.util.Log
import com.jarvis.wakeword.DsCnnWakeWordDetector
import com.jarvis.wakeword.HybridWakeWordDetector
import com.jarvis.wakeword.UserVoiceProfile
import com.jarvis.wakeword.OwnerVoiceProfile
import com.jarvis.wakeword.TfliteOwnerVoiceVerifier
import com.jarvis.wakeword.WakeWordConfig
import com.jarvis.wakeword.WakeWordDetector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

enum class WakeWordState {
    STOPPED,
    RUNNING,
    TRIGGERED,
    CLOSED
}

/**
 * Wake word engine using continuous PCM audio stream + TFLite KWS detector with acoustic fallback.
 * Uses AudioRecord directly without requesting audio focus, allowing background media
 * (YouTube, Spotify, etc.) to play uninterrupted without pausing or ducking.
 *
 * State lifecycle:
 * - start(): Marks RUNNING, resets detector state, sets callback.
 * - acceptAudio(): Evaluates frame if RUNNING; transitions to TRIGGERED on detection.
 * - stop(): Stops accepting audio, remains loaded.
 * - reset(): Clears detection history and state, returns to RUNNING.
 * - close(): Permanently frees TFLite interpreter and buffers.
 */
class WakeWordEngine(
    private val context: Context? = null,
    private var config: WakeWordConfig = WakeWordConfig(),
    private val customDetector: WakeWordDetector? = null
) {
    private val TAG = "WakeWordEngine"
    private var detector: WakeWordDetector? = customDetector
    private var activeProfileIndex: Int = 0
    private val detectionGate = AtomicBoolean(false)
    private val ownerCheckInFlight = AtomicBoolean(false)
    private val ownerGeneration = AtomicInteger(0)
    private var currentVoiceProfile: UserVoiceProfile = UserVoiceProfile.DEFAULT
    @Volatile private var ownerVoiceOnly = false
    @Volatile private var ownerProfile: OwnerVoiceProfile? = null
    @Volatile private var ownerVerifier: TfliteOwnerVoiceVerifier? = null
    @Volatile private var lastOwnerCheckAt = 0L
    private val ownerAudio = ShortArray(48_000)
    private var ownerAudioSize = 0
    private val ownerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-owner-voice").apply { isDaemon = true }
    }

    @Volatile
    private var state = WakeWordState.STOPPED
    private var onDetectedCallback: ((String) -> Unit)? = null
    private var lastDetectionAt: Long = 0L
    @Volatile
    private var trailingAudioBuffer: ShortArray? = null

    private fun ensureDetectorInitialized() {
        if (detector == null) {
            val profile = config.profiles.getOrNull(activeProfileIndex)
            val profileConfig = if (currentVoiceProfile.isEnrolled) {
                config.copy(
                    detectionThreshold = currentVoiceProfile.calibratedThreshold,
                    minEnergyRms = currentVoiceProfile.calibratedMinEnergyRms
                )
            } else if (profile != null) {
                config.copy(
                    detectionThreshold = profile.threshold
                )
            } else config
            detector = HybridWakeWordDetector(
                context = context,
                modelAssetPath = profile?.modelAssetPath ?: "jarvis_dscnn_int8.tflite",
                config = profileConfig
            )
        }
    }

    @Synchronized
    fun applyVoiceProfile(profile: UserVoiceProfile) {
        currentVoiceProfile = profile
        val targetThreshold = if (profile.isEnrolled) {
            profile.calibratedThreshold.coerceIn(0.50f, 0.75f)
        } else {
            config.profiles.getOrNull(activeProfileIndex)?.threshold ?: 0.65f
        }
        val targetMinEnergy = if (profile.isEnrolled) {
            profile.calibratedMinEnergyRms.coerceIn(10.0, 45.0)
        } else {
            15.0
        }

        config = config.copy(
            detectionThreshold = targetThreshold,
            minEnergyRms = targetMinEnergy
        )

        if (customDetector == null) {
            detector?.close()
            detector = null
            ensureDetectorInitialized()
        }
        Log.i(TAG, "Applied voice profile (enrolled=${profile.isEnrolled}, threshold=${config.effectiveThreshold()}, minEnergy=$targetMinEnergy)")
    }

    @Synchronized
    fun configureOwnerVoiceWake(enabled: Boolean, profile: OwnerVoiceProfile?) {
        if (enabled == ownerVoiceOnly && profile?.enrolledAt == ownerProfile?.enrolledAt && profile?.threshold == ownerProfile?.threshold) return
        ownerGeneration.incrementAndGet()
        ownerVoiceOnly = enabled
        ownerProfile = profile
        detectionGate.set(false)
        clearOwnerAudio()
        ownerVerifier?.close()
        ownerVerifier = if (enabled && profile != null) context?.let { ctx ->
            runCatching { TfliteOwnerVoiceVerifier(ctx) }.getOrNull()
        } else null
        detector?.resetDetectorState()
        Log.i(TAG, "Owner-only wake configured (enabled=$enabled, profile=${profile != null}, verifierReady=${ownerVerifier?.isAvailable() == true})")
    }

    fun getVoiceProfile(): UserVoiceProfile = currentVoiceProfile

    fun getEffectiveThreshold(): Float = config.effectiveThreshold()

    @Synchronized
    fun availableWakeWords(): List<String> = config.profiles.map { it.phrase }

    @Synchronized
    fun setActiveWakeWord(phrase: String): Boolean {
        val index = config.profiles.indexOfFirst { it.phrase.equals(phrase, ignoreCase = true) }
        if (index < 0) return false
        activeProfileIndex = index
        detector?.close()
        detector = null
        ensureDetectorInitialized()
        return true
    }

    fun getTrailingAudio(): ShortArray? = trailingAudioBuffer

    fun isAvailable(): Boolean {
        ensureDetectorInitialized()
        if (ownerVoiceOnly) {
            return ownerProfile != null && ownerVerifier?.isAvailable() == true &&
                (detector as? HybridWakeWordDetector)?.isNeuralModelActive() == true
        }
        return detector?.isModelAvailable() == true
    }

    fun getDetectorMode(): com.jarvis.wakeword.WakeDetectorMode {
        ensureDetectorInitialized()
        return (detector as? HybridWakeWordDetector)?.getDetectorMode()
            ?: if (detector?.isModelAvailable() == true) com.jarvis.wakeword.WakeDetectorMode.NEURAL_READY else com.jarvis.wakeword.WakeDetectorMode.UNAVAILABLE
    }

    fun getModelError(): String? {
        ensureDetectorInitialized()
        if (ownerVoiceOnly) return ownerVerifier?.error() ?: if (ownerProfile == null) "Owner voice profile is not enrolled" else "Neural wake model unavailable"
        return detector?.getModelLoadError()
    }

    fun start(@Suppress("UNUSED_PARAMETER") kwsDir: File? = null, onDetected: ((String) -> Unit)? = null) {
        if (state == WakeWordState.CLOSED) {
            Log.i(TAG, "Re-opening previously closed wake engine")
            state = WakeWordState.STOPPED
            detector = null
        }

        ensureDetectorInitialized()
        onDetectedCallback = onDetected
        detectionGate.set(false)
        detector?.resetDetectorState()
        clearOwnerAudio()
        lastDetectionAt = 0L
        state = WakeWordState.RUNNING

        Log.i(TAG, "WakeWordEngine started (state=$state, modelAvailable=${isAvailable()})")
    }

    fun acceptAudio(samples: ShortArray): Boolean {
        if (state != WakeWordState.RUNNING || detectionGate.get() || samples.isEmpty()) {
            return false
        }

        val det = detector ?: return false
        if (ownerVoiceOnly) appendOwnerAudio(samples)
        val score = if (ownerVoiceOnly) {
            (det as? HybridWakeWordDetector)?.pushNeuralAudio(samples) ?: 0f
        } else det.pushAudio(samples)
        val now = System.currentTimeMillis()
        val threshold = config.effectiveThreshold()

        if (score >= threshold && now - lastDetectionAt >= config.detectionCooldownMs) {
            if (ownerVoiceOnly) {
                if (now - lastOwnerCheckAt < OWNER_CHECK_INTERVAL_MS || !isAvailable()) return false
                if (ownerCheckInFlight.compareAndSet(false, true)) {
                    lastOwnerCheckAt = now
                    val generation = ownerGeneration.get()
                    val candidate = ownerAudioSnapshot()
                    val triggerFrame = samples.copyOf()
                    ownerExecutor.execute {
                        var retainedTriggerFrame = false
                        try {
                            val embedding = ownerVerifier?.embed(candidate)
                            val profile = ownerProfile
                            val modelsReady = ownerVerifier?.isAvailable() == true && (detector as? HybridWakeWordDetector)?.isNeuralModelActive() == true
                            val similarity = embedding?.let { profile?.similarity(it) }
                            val accepted = TfliteOwnerVoiceVerifier.shouldWake(
                                neuralScore = score,
                                kwsThreshold = threshold,
                                similarity = similarity,
                                speakerThreshold = profile?.threshold ?: 1f,
                                modelsReady = modelsReady,
                                profileReady = profile != null
                            )
                            synchronized(this) {
                                if (generation == ownerGeneration.get() && state == WakeWordState.RUNNING) {
                                    if (accepted && detectionGate.compareAndSet(false, true)) {
                                        state = WakeWordState.TRIGGERED
                                        lastDetectionAt = System.currentTimeMillis()
                                        val phrase = config.profiles.getOrNull(activeProfileIndex)?.phrase ?: "Jarvis"
                                        trailingAudioBuffer = triggerFrame
                                        retainedTriggerFrame = true
                                        clearOwnerAudio()
                                        Log.i(TAG, "Owner-verified wake '$phrase' (score=$score)")
                                        onDetectedCallback?.invoke(phrase)
                                    } else {
                                        detectionGate.set(false)
                                        Log.i(TAG, "Owner wake candidate rejected (embedding=${embedding != null}, modelsReady=$modelsReady, profile=${profile != null})")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Owner verification failed closed", t)
                            if (generation == ownerGeneration.get() && state == WakeWordState.RUNNING) detectionGate.set(false)
                        } finally {
                            candidate.fill(0)
                            if (!retainedTriggerFrame) triggerFrame.fill(0)
                            ownerCheckInFlight.set(false)
                        }
                    }
                }
                return false
            }
            if (detectionGate.compareAndSet(false, true)) {
                trailingAudioBuffer = samples.copyOf()
                state = WakeWordState.TRIGGERED
                lastDetectionAt = now
                val phrase = config.profiles.getOrNull(activeProfileIndex)?.phrase ?: "Jarvis"
                Log.i(TAG, "Wake word '$phrase' detected in PCM stream with score: $score")
                onDetectedCallback?.invoke(phrase)
                return true
            }
        }
        return false
    }

    fun stop() {
        if (state == WakeWordState.CLOSED) return
        state = WakeWordState.STOPPED
        ownerGeneration.incrementAndGet()
        detectionGate.set(false)
        clearOwnerAudio()
        onDetectedCallback = null
        Log.i(TAG, "WakeWordEngine stopped listening")
    }

    fun reset() {
        if (state == WakeWordState.CLOSED) return
        detectionGate.set(false)
        ownerGeneration.incrementAndGet()
        clearOwnerAudio()
        detector?.resetDetectorState()
        lastDetectionAt = 0L
        state = WakeWordState.RUNNING
        Log.d(TAG, "WakeWordEngine reset to RUNNING")
    }

    fun close() {
        state = WakeWordState.CLOSED
        ownerGeneration.incrementAndGet()
        detectionGate.set(false)
        onDetectedCallback = null
        detector?.close()
        detector = null
        ownerVerifier?.close()
        ownerVerifier = null
        clearOwnerAudio()
        Log.i(TAG, "WakeWordEngine closed and released")
    }

    @Synchronized private fun appendOwnerAudio(samples: ShortArray) {
        if (samples.size >= ownerAudio.size) {
            samples.copyInto(ownerAudio, 0, samples.size - ownerAudio.size)
            ownerAudioSize = ownerAudio.size
            return
        }
        val removed = (ownerAudioSize + samples.size - ownerAudio.size).coerceAtLeast(0)
        if (removed > 0) { ownerAudio.copyInto(ownerAudio, 0, removed, ownerAudioSize); ownerAudioSize -= removed }
        samples.copyInto(ownerAudio, ownerAudioSize)
        ownerAudioSize += samples.size
    }

    @Synchronized private fun ownerAudioSnapshot() = ownerAudio.copyOf(ownerAudioSize)
    @Synchronized private fun clearOwnerAudio() { ownerAudio.fill(0); ownerAudioSize = 0 }

    companion object { private const val OWNER_CHECK_INTERVAL_MS = 700L }
}
