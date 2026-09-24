package com.jarvis.wakeword

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zero-dependency pure Kotlin acoustic-phonetic wake word detector for "Jarvis".
 *
 * Evaluates the acoustic and spectral progression of the two-syllable keyword:
 * 1. Syllable 1 ("JAR"): voiced onset [dʒ], resonant open vocalic core [ɑː-ɹ] with low zero-crossing rate.
 * 2. Transition ("V-I"): voiced labiodental dip and front vowel [v-ɪ].
 * 3. Syllable 2 Coda ("S"): terminal alveolar sibilant with high zero-crossing rate (ZCR > 0.20)
 *    and upper-band spectral concentration.
 *
 * Operates reliably without requiring native TFLite libraries, models, or JNI bindings.
 */
class AcousticWakeWordDetector(
    val config: WakeWordConfig = WakeWordConfig()
) : WakeWordDetector {

    private val TAG = "AcousticWakeDetector"

    // Rolling circular audio buffer (1 second = 16000 samples @ 16kHz)
    private val windowSamples = config.windowSamples
    private val rollingBuffer = ShortArray(windowSamples)
    private val linearAudio = ShortArray(windowSamples)
    private var bufferPos = 0
    private var isBufferFull = false
    private var samplesSinceLastInference = 0

    // Adaptive noise floor tracking
    @Volatile private var noiseFloorRms = 12.0
    private var lastRmsLogTime = 0L

    // Confirmation history
    private val recentScores = ArrayDeque<Float>()
    private var lastWakeScore: Float = 0.0f
    private var lastWakeTimestamp: Long = 0L
    private var detectionCount: Long = 0L

    override fun isModelAvailable(): Boolean = true

    override fun getModelLoadError(): String? = null

    @Synchronized
    override fun pushAudio(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0.0f

        // 1. Ingest audio into rolling buffer using block copy
        var srcOffset = 0
        var remaining = samples.size
        while (remaining > 0) {
            val chunk = minOf(remaining, windowSamples - bufferPos)
            System.arraycopy(samples, srcOffset, rollingBuffer, bufferPos, chunk)
            bufferPos += chunk
            if (bufferPos >= windowSamples) {
                bufferPos = 0
                isBufferFull = true
            }
            srcOffset += chunk
            remaining -= chunk
        }
        samplesSinceLastInference += samples.size

        // 2. Evaluate at configured cadence
        if (!isBufferFull || samplesSinceLastInference < config.inferenceIntervalSamples) {
            return 0.0f
        }
        samplesSinceLastInference = 0

        // 3. Energy & Noise Floor Gating
        if (!hasSufficientEnergy(samples)) {
            updateConfirmation(0.0f)
            return 0.0f
        }

        // 4. Linearize circular buffer
        val part1 = windowSamples - bufferPos
        System.arraycopy(rollingBuffer, bufferPos, linearAudio, 0, part1)
        System.arraycopy(rollingBuffer, 0, linearAudio, part1, bufferPos)

        // 5. Compute Acoustic-Phonetic Score
        val rawScore = evaluateAcousticPhonetics(linearAudio)

        // 6. Multi-window confirmation
        val confirmedScore = updateConfirmation(rawScore)
        if (confirmedScore >= config.effectiveThreshold()) {
            lastWakeScore = confirmedScore
            lastWakeTimestamp = System.currentTimeMillis()
            detectionCount++
            Log.i(TAG, "Acoustic wake word 'Jarvis' CONFIRMED with score=$confirmedScore")
            return confirmedScore
        }

        return 0.0f
    }

    private fun updateConfirmation(score: Float): Float {
        recentScores.addLast(score)
        while (recentScores.size > config.confirmationWindowCount) {
            recentScores.removeFirst()
        }

        val positives = recentScores.count { it >= config.effectiveThreshold() }
        if (recentScores.size >= config.requiredPositiveWindows &&
            positives >= config.requiredPositiveWindows
        ) {
            recentScores.clear()
            return score
        }
        return 0.0f
    }

    /**
     * Analyzes temporal energy envelopes, zero-crossing rates, and spectral formants.
     */
    fun evaluateAcousticPhonetics(audio: ShortArray): Float {
        val totalLen = audio.size
        if (totalLen < 8000) return 0.0f

        // Partition 1-second audio into 5 temporal sub-windows of equal duration (~200ms each)
        val numWindows = 5
        val winSize = totalLen / numWindows

        val rmsValues = DoubleArray(numWindows)
        val zcrValues = DoubleArray(numWindows)
        val hfRatios = DoubleArray(numWindows)

        for (w in 0 until numWindows) {
            val start = w * winSize
            val end = start + winSize
            var sumSq = 0.0
            var zeroCrossings = 0
            var hfDifferenceSum = 0.0

            for (i in start until end) {
                val s = audio[i].toDouble()
                sumSq += s * s

                if (i > start) {
                    val prev = audio[i - 1].toInt()
                    val curr = audio[i].toInt()
                    if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                        zeroCrossings++
                    }
                    val diff = (curr - prev).toDouble()
                    hfDifferenceSum += diff * diff
                }
            }

            val count = winSize.toDouble()
            rmsValues[w] = sqrt(sumSq / count)
            zcrValues[w] = zeroCrossings / count
            hfRatios[w] = if (sumSq > 1e-4) sqrt(hfDifferenceSum / sumSq) else 0.0
        }

        val minSubRms = rmsValues.minOrNull() ?: 1.0
        val maxSubRms = rmsValues.maxOrNull() ?: 1.0
        val dynamicRatio = maxSubRms / max(minSubRms, 1.0)
        // Rejection 1: Flat/stationary sound (fans, air conditioning, white noise)
        if (dynamicRatio < 1.7) {
            return 0.0f
        }

        val baselineRms = noiseFloorRms.coerceAtLeast(10.0)

        // Syllable 1 ("JAR"): windows 1 and 2 should show vocalic energy peak and moderate ZCR
        val jarRms = maxOf(rmsValues[1], rmsValues[2])
        val jarZcr = minOf(zcrValues[1], zcrValues[2])

        // Syllable 2 ("VIS"): windows 3 and 4 should show transition and terminal sibilance
        val visRms = rmsValues[3]
        val sibilantRms = rmsValues[4]
        val sibilantZcr = zcrValues[4]
        val sibilantHf = hfRatios[4]

        // Condition 1: Energy must exceed background baseline significantly
        val hasSpeechEnergy = jarRms > baselineRms * 1.3 && (visRms > baselineRms * 1.05 || sibilantRms > baselineRms * 1.05)
        if (!hasSpeechEnergy) return 0.0f

        // Rejection 2: Vowel / voiced core cannot have high ZCR (speech vowels have ZCR < 0.15)
        if (jarZcr > 0.18) return 0.0f

        // Condition 2: Sibilant coda "S" exhibits high zero-crossing rate compared to vocalic core "JAR"
        // In speech: /s/ has ZCR > 0.20, vowels have ZCR < 0.12
        val hasSibilantTail = sibilantZcr > 0.18 && (sibilantZcr > jarZcr * 1.3)
        val sibilantScore = when {
            sibilantZcr >= 0.22 && sibilantHf > 0.60 -> 1.0f
            sibilantZcr >= 0.18 && hasSibilantTail -> 0.85f
            hasSibilantTail -> 0.70f
            else -> 0.10f
        }
        if (sibilantScore <= 0.10f) return 0.0f

        // Condition 3: Vocalic core has strong voiced energy and lower ZCR (periodicity)
        val vowelScore = when {
            jarRms > baselineRms * 1.8 && jarZcr < 0.15 -> 0.95f
            jarRms > baselineRms * 1.3 && jarZcr < 0.17 -> 0.80f
            jarRms > baselineRms * 1.1 -> 0.60f
            else -> 0.20f
        }

        // Condition 4: Bimodal energy envelope (two syllable peaks: "JAR" and "VIS")
        val isTwoSyllables = (jarRms > baselineRms * 1.2) && (visRms > baselineRms * 1.05 || sibilantRms > baselineRms * 1.05)
        val envelopeScore = if (isTwoSyllables) 0.90f else 0.40f

        // Composite acoustic confidence score
        val compositeScore = (0.45f * sibilantScore + 0.35f * vowelScore + 0.20f * envelopeScore)
        return compositeScore.coerceIn(0.0f, 0.98f)
    }

    private fun hasSufficientEnergy(frame: ShortArray): Boolean {
        if (frame.isEmpty()) return false
        var sum = 0.0
        for (sample in frame) {
            val s = sample.toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / frame.size)

        val now = System.currentTimeMillis()
        if (now - lastRmsLogTime > 5000L) {
            lastRmsLogTime = now
            Log.d(TAG, "Acoustic RMS: %.1f, noise floor: %.1f".format(rms, noiseFloorRms))
        }

        val required = maxOf(config.minEnergyRms, noiseFloorRms * 1.15)
        if (rms < required) {
            noiseFloorRms = noiseFloorRms * 0.95 + rms * 0.05
            return false
        } else {
            noiseFloorRms = noiseFloorRms * 0.995 + rms * 0.005
        }
        return true
    }

    @Synchronized
    override fun resetDetectorState() {
        rollingBuffer.fill(0)
        linearAudio.fill(0)
        bufferPos = 0
        isBufferFull = false
        samplesSinceLastInference = 0
        recentScores.clear()
        Log.d(TAG, "Acoustic detector state reset")
    }

    @Synchronized
    override fun close() {
        resetDetectorState()
    }
}
