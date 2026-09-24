package com.jarvis.wakeword

import kotlin.math.*

class MfccExtractor(
    val sampleRate: Int = 16000,
    val numMfcc: Int = 10,
    val numMelFilters: Int = 40,
    val frameLengthSamples: Int = 400, // 25ms @ 16kHz
    val frameStrideSamples: Int = 320,  // 20ms @ 16kHz
    val fftSize: Int = 512
) {
    private val hammingWindow = FloatArray(frameLengthSamples) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (frameLengthSamples - 1))).toFloat()
    }

    private val melFilterBank: Array<FloatArray> = computeMelFilterBank()
    private val dctMatrix: Array<FloatArray> = computeDctMatrix()

    // Pre-allocated scratch buffers to eliminate per-frame allocations
    private val frameBuffer = FloatArray(fftSize)
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val powerSpectrum = FloatArray(fftSize / 2 + 1)
    private val melEnergies = FloatArray(numMelFilters)

    @Synchronized
    fun extractMfcc(audioSamples: ShortArray, output: Array<FloatArray>): Int {
        val numFrames = minOf((audioSamples.size - frameLengthSamples) / frameStrideSamples + 1, output.size)
        if (numFrames <= 0) return 0

        for (f in 0 until numFrames) {
            val start = f * frameStrideSamples
            var prev = 0.0f
            for (i in 0 until frameLengthSamples) {
                val raw = audioSamples[start + i].toFloat() / 32768.0f
                val emphasized = raw - 0.97f * prev
                prev = raw
                frameBuffer[i] = emphasized * hammingWindow[i]
            }
            for (i in frameLengthSamples until fftSize) {
                frameBuffer[i] = 0.0f
            }

            System.arraycopy(frameBuffer, 0, real, 0, fftSize)
            imag.fill(0.0f)
            fft(real, imag)

            for (i in powerSpectrum.indices) {
                powerSpectrum[i] = (real[i] * real[i] + imag[i] * imag[i]) / fftSize
            }

            for (m in 0 until numMelFilters) {
                var sum = 0.0f
                val filter = melFilterBank[m]
                for (k in filter.indices) {
                    sum += powerSpectrum[k] * filter[k]
                }
                melEnergies[m] = ln(max(sum, 1e-6f))
            }

            for (i in 0 until numMfcc) {
                var sum = 0.0f
                for (j in 0 until numMelFilters) {
                    sum += melEnergies[j] * dctMatrix[i][j]
                }
                output[f][i] = sum
            }
        }
        return numFrames
    }

    fun quantizeToInt8(
        features: Array<FloatArray>,
        numFrames: Int,
        outBytes: ByteArray,
        scale: Float = 0.1f,
        zeroPoint: Int = 0
    ): Int {
        val effectiveScale = if (scale <= 0.0f) 0.1f else scale
        val maxFrames = minOf(features.size, numFrames)
        var idx = 0
        for (f in 0 until maxFrames) {
            for (c in 0 until numMfcc) {
                val realVal = features[f][c]
                val q = (round(realVal / effectiveScale) + zeroPoint).toInt().coerceIn(-128, 127)
                outBytes[idx++] = q.toByte()
            }
        }
        return idx
    }

    fun copyToFloatArray(
        features: Array<FloatArray>,
        numFrames: Int,
        outFloats: FloatArray
    ): Int {
        val maxFrames = minOf(features.size, numFrames)
        var idx = 0
        for (f in 0 until maxFrames) {
            for (c in 0 until numMfcc) {
                outFloats[idx++] = features[f][c]
            }
        }
        return idx
    }

    private fun hzToMel(hz: Float): Float = 2595.0f * log10(1.0f + hz / 700.0f)
    private fun melToHz(mel: Float): Float = 700.0f * (10.0f.pow(mel / 2595.0f) - 1.0f)

    private fun computeMelFilterBank(): Array<FloatArray> {
        val lowMel = hzToMel(20.0f)
        val highMel = hzToMel(sampleRate / 2.0f)
        val melPoints = FloatArray(numMelFilters + 2)
        val deltaMel = (highMel - lowMel) / (numMelFilters + 1)

        for (i in melPoints.indices) {
            melPoints[i] = lowMel + i * deltaMel
        }

        val bin = IntArray(numMelFilters + 2) { i ->
            val hz = melToHz(melPoints[i])
            floor((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, fftSize / 2)
        }

        val numBins = fftSize / 2 + 1
        val filters = Array(numMelFilters) { FloatArray(numBins) }

        for (m in 1..numMelFilters) {
            val fLeft = bin[m - 1]
            val fCenter = bin[m]
            val fRight = bin[m + 1]

            for (k in fLeft until fCenter) {
                if (fCenter != fLeft) {
                    filters[m - 1][k] = (k - fLeft).toFloat() / (fCenter - fLeft)
                }
            }
            for (k in fCenter until fRight) {
                if (fRight != fCenter) {
                    filters[m - 1][k] = (fRight - k).toFloat() / (fRight - fCenter)
                }
            }
        }
        return filters
    }

    private fun computeDctMatrix(): Array<FloatArray> {
        val matrix = Array(numMfcc) { FloatArray(numMelFilters) }
        val factor = sqrt(2.0f / numMelFilters)
        for (i in 0 until numMfcc) {
            for (j in 0 until numMelFilters) {
                matrix[i][j] = (factor * cos(PI * i * (j + 0.5) / numMelFilters)).toFloat()
            }
        }
        return matrix
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var l = 1
        while (l < n) {
            val step = l * 2
            val angle = -PI / l
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var uR = 1.0f
            var uI = 0.0f
            for (m in 0 until l) {
                for (i in m until n step step) {
                    val pair = i + l
                    val tr = uR * real[pair] - uI * imag[pair]
                    val ti = uR * imag[pair] + uI * real[pair]
                    real[pair] = real[i] - tr
                    imag[pair] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                }
                val nextUR = uR * wStepR - uI * wStepI
                val nextUI = uR * wStepI + uI * wStepR
                uR = nextUR
                uI = nextUI
            }
            l = step
        }
    }
}
