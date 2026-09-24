package com.jarvis.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Production Depthwise-Separable CNN (DS-CNN) / TFLite Wake Word Detector for "Jarvis".
 *
 * Requirements enforced:
 * 1. Strict TFLite model loading: no fake mathematical or acoustic heuristics.
 * 2. Missing/corrupt model cleanly disables wake detection (score = 0.0f).
 * 3. Dynamic INT8 / FLOAT32 input quantization and output dequantization based on tensor metadata.
 * 4. Multi-window sliding confirmation (e.g., 2 positive inferences in a 3-window history).
 * 5. Adaptive ambient noise-floor gating (calibrated during passive listening).
 * 6. Complete state reset via [resetDetectorState].
 */
class DsCnnWakeWordDetector(
    private val context: Context,
    val modelAssetPath: String = "jarvis_dscnn_int8.tflite",
    val config: WakeWordConfig = WakeWordConfig()
) : WakeWordDetector {

    private val mfccExtractor = MfccExtractor(numMfcc = NUM_MFCC, numMelFilters = 40)
    private var tfliteInterpreter: Interpreter? = null

    private var modelAvailable = false
    private var modelLoadError: String? = null

    // Rolling circular audio buffer
    private val rollingBuffer = ShortArray(config.windowSamples)
    private var bufferPos = 0
    private var isBufferFull = false
    private var samplesSinceLastInference = 0

    // Sliding confirmation window
    private val recentScores = ArrayDeque<Float>()

    // Adaptive noise floor & energy tracking
    @Volatile private var noiseFloorRms = 12.0
    private var lastRmsLogTime = 0L

    // Diagnostics
    private var inputDataType: DataType? = null
    private var outputDataType: DataType? = null
    private var inputShapeStr: String = ""
    private var outputShapeStr: String = ""
    // Stored output shape so runInference() can determine class count dynamically (BUG-023 fix)
    private var outputShape: IntArray = IntArray(0)
    private var inputScale: Float = 0.1f
    private var inputZeroPoint: Int = 0
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var lastWakeScore: Float = 0.0f
    private var lastWakeTimestamp: Long = 0L
    private var detectionCount: Long = 0L

    // Pre-allocated buffers to prevent GC allocations during continuous audio processing
    private val linearAudio = ShortArray(config.windowSamples)
    private val mfccMatrix = Array(NUM_TIME_FRAMES) { FloatArray(NUM_MFCC) }
    private val quantizedBytes = ByteArray(NUM_TIME_FRAMES * NUM_MFCC)
    private val floatFeatures = FloatArray(NUM_TIME_FRAMES * NUM_MFCC)

    private var inputByteBuffer: ByteBuffer? = null
    private var outputByteBuffer: ByteBuffer? = null
    private var isInitialized = false

    @Synchronized
    private fun ensureTfliteInitialized() {
        if (isInitialized) return
        isInitialized = true
        initTflite()
    }

    private fun initTflite() {
        try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd(modelAssetPath)
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val modelBuffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )

                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                    setUseNNAPI(false)
                }

                val interpreter = Interpreter(modelBuffer, options)
                try {
                    validateAndConfigureModel(interpreter)
                    tfliteInterpreter = interpreter
                } catch (t: Throwable) {
                    interpreter.close()  // close local var — tfliteInterpreter is still null here
                    throw t
                }

                modelAvailable = true
                modelLoadError = null
                Log.i(TAG, "Wake model loaded successfully: $modelAssetPath (input=$inputShapeStr $inputDataType, output=$outputShapeStr $outputDataType)")
            }
        } catch (t: Throwable) {
            tfliteInterpreter?.close()
            tfliteInterpreter = null
            modelAvailable = false
            modelLoadError = t.message ?: "Unknown model loading error"
            Log.e(TAG, "Wake model ($modelAssetPath) unavailable. Wake detection disabled: $modelLoadError")
        }
    }

    private fun validateAndConfigureModel(interpreter: Interpreter) {
        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)

        val inputShape = input.shape()
        val outputShape = output.shape()

        inputShapeStr = inputShape.contentToString()
        outputShapeStr = outputShape.contentToString()
        this.outputShape = outputShape  // persist for dynamic class-count reading in runInference()
        inputDataType = input.dataType()
        outputDataType = output.dataType()

        Log.i(TAG, "Wake model input shape=$inputShapeStr type=$inputDataType, output shape=$outputShapeStr type=$outputDataType")

        require(inputShape.isNotEmpty()) { "Wake model input tensor shape is invalid" }
        require(outputShape.isNotEmpty()) { "Wake model output tensor shape is invalid" }

        val inQuant = input.quantizationParams()
        inputScale = if (inQuant.scale > 0f) inQuant.scale else 0.1f
        inputZeroPoint = inQuant.zeroPoint

        val outQuant = output.quantizationParams()
        outputScale = if (outQuant.scale > 0f) outQuant.scale else 1.0f
        outputZeroPoint = outQuant.zeroPoint

        // Allocate I/O ByteBuffers based on data type
        val inputByteSize = when (input.dataType()) {
            DataType.INT8, DataType.UINT8 -> NUM_TIME_FRAMES * NUM_MFCC
            DataType.FLOAT32 -> NUM_TIME_FRAMES * NUM_MFCC * 4
            else -> throw IllegalStateException("Unsupported wake model input tensor type: ${input.dataType()}")
        }
        inputByteBuffer = ByteBuffer.allocateDirect(inputByteSize).apply {
            order(ByteOrder.nativeOrder())
        }

        val outputClasses = if (outputShape.size > 1) outputShape[1] else outputShape[0]
        val outputByteSize = when (output.dataType()) {
            DataType.INT8, DataType.UINT8 -> outputClasses
            DataType.FLOAT32 -> outputClasses * 4
            else -> throw IllegalStateException("Unsupported wake model output tensor type: ${output.dataType()}")
        }
        outputByteBuffer = ByteBuffer.allocateDirect(outputByteSize).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    override fun isModelAvailable(): Boolean {
        ensureTfliteInitialized()
        return modelAvailable
    }

    override fun getModelLoadError(): String? {
        ensureTfliteInitialized()
        return modelLoadError
    }

    @Synchronized
    override fun pushAudio(samples: ShortArray): Float {
        ensureTfliteInitialized()
        if (!modelAvailable || samples.isEmpty()) return 0.0f

        // Ingest audio into rolling circular buffer
        for (sample in samples) {
            rollingBuffer[bufferPos] = sample
            bufferPos = (bufferPos + 1) % config.windowSamples
            if (bufferPos == 0) {
                isBufferFull = true
            }
        }

        samplesSinceLastInference += samples.size

        // Evaluate at configured sample cadence
        if (!isBufferFull || samplesSinceLastInference < config.inferenceIntervalSamples) {
            return 0.0f
        }
        samplesSinceLastInference = 0

        // Energy & noise-floor gate: skip low energy / silence frames to save CPU
        if (!hasSufficientEnergy(samples)) {
            updateConfirmation(0.0f)
            return 0.0f
        }

        // Linearize circular buffer
        val part1 = config.windowSamples - bufferPos
        System.arraycopy(rollingBuffer, bufferPos, linearAudio, 0, part1)
        System.arraycopy(rollingBuffer, 0, linearAudio, part1, bufferPos)

        // Extract MFCC features
        val numFrames = mfccExtractor.extractMfcc(linearAudio, mfccMatrix)
        if (numFrames < NUM_TIME_FRAMES) return 0.0f

        // Run real model inference (no heuristic fallback!)
        val inferenceScore = runInference(numFrames)

        // Pass through multi-window confirmation
        val confirmedScore = updateConfirmation(inferenceScore)
        if (confirmedScore >= config.effectiveThreshold()) {
            lastWakeScore = confirmedScore
            lastWakeTimestamp = System.currentTimeMillis()
            detectionCount++
            Log.i(TAG, "Wake word 'Jarvis' CONFIRMED with score=$confirmedScore (history=$recentScores)")
            return confirmedScore
        }

        return 0.0f
    }

    private fun updateConfirmation(score: Float): Float {
        if (config.requiredPositiveWindows <= 1 && score >= config.effectiveThreshold()) {
            recentScores.clear()
            return score
        }

        recentScores.addLast(score)
        while (recentScores.size > config.confirmationWindowCount) {
            recentScores.removeFirst()
        }

        val positives = recentScores.count { it >= config.effectiveThreshold() }

        if (recentScores.size >= config.confirmationWindowCount &&
            positives >= config.requiredPositiveWindows
        ) {
            recentScores.clear()
            return score
        }

        return 0.0f
    }

    private fun runInference(numFrames: Int): Float {
        val interpreter = tfliteInterpreter ?: return 0.0f
        val inBuf = inputByteBuffer ?: return 0.0f
        val outBuf = outputByteBuffer ?: return 0.0f

        return try {
            inBuf.rewind()
            outBuf.rewind()

            when (inputDataType) {
                DataType.INT8, DataType.UINT8 -> {
                    val count = mfccExtractor.quantizeToInt8(
                        mfccMatrix,
                        numFrames,
                        quantizedBytes,
                        inputScale,
                        inputZeroPoint
                    )
                    inBuf.put(quantizedBytes, 0, count)
                }
                DataType.FLOAT32 -> {
                    val count = mfccExtractor.copyToFloatArray(mfccMatrix, numFrames, floatFeatures)
                    for (i in 0 until count) {
                        inBuf.putFloat(floatFeatures[i])
                    }
                }
                else -> return 0.0f
            }

            inBuf.rewind()
            interpreter.run(inBuf, outBuf)
            outBuf.rewind()

            val outputClasses = if (outputShape.size > 1) outputShape[1] else outputShape[0]

            val rawScores = when (outputDataType) {
                DataType.INT8 -> {
                    FloatArray(outputClasses) { dequantize(outBuf.get().toInt(), outputScale, outputZeroPoint) }
                }
                DataType.UINT8 -> {
                    FloatArray(outputClasses) { dequantize(outBuf.get().toInt() and 0xFF, outputScale, outputZeroPoint) }
                }
                DataType.FLOAT32 -> {
                    FloatArray(outputClasses) { outBuf.float }
                }
                else -> return 0.0f
            }

            if (JARVIS_CLASS_INDEX >= rawScores.size) {
                Log.e(TAG, "JARVIS_CLASS_INDEX ($JARVIS_CLASS_INDEX) out of bounds for model with ${rawScores.size} output classes")
                return 0.0f
            }

            // Interpret output classes: [0 = silence/background, 1 = other speech, 2 = Jarvis]
            // If output values are unnormalized logits, apply softmax.
            val jarvisScore = if (isProbabilities(rawScores)) {
                rawScores[JARVIS_CLASS_INDEX]
            } else {
                softmax(rawScores)[JARVIS_CLASS_INDEX]
            }

            if (jarvisScore > 0.35f) {
                Log.d(TAG, "Inference raw Jarvis score=$jarvisScore (threshold=${config.effectiveThreshold()})")
            }

            jarvisScore
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference execution error", e)
            0.0f
        }
    }

    private fun dequantize(value: Int, scale: Float, zeroPoint: Int): Float {
        return (value - zeroPoint) * scale
    }

    private fun isProbabilities(scores: FloatArray): Boolean {
        return scores.all { it in 0.0f..1.05f } && scores.sum() in 0.90f..1.10f
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0.0f
        val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sumExp = exps.sum()
        return if (sumExp > 0.0f) FloatArray(logits.size) { exps[it] / sumExp } else logits
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
        if (now - lastRmsLogTime > 4000L) {
            lastRmsLogTime = now
            Log.d(TAG, "Ambient audio RMS: %.1f, noise floor: %.1f".format(rms, noiseFloorRms))
        }

        // Calibrate noise floor on ambient audio
        val required = maxOf(config.minEnergyRms, noiseFloorRms * 1.15)
        if (rms < required) {
            noiseFloorRms = noiseFloorRms * 0.96 + rms * 0.04
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
        Log.d(TAG, "Detector state fully reset")
    }

    @Synchronized
    override fun close() {
        tfliteInterpreter?.close()
        tfliteInterpreter = null
        modelAvailable = false
        isInitialized = false
        inputByteBuffer = null
        outputByteBuffer = null
        Log.i(TAG, "DsCnnWakeWordDetector closed and resources released")
    }

    // Diagnostics getters
    fun getDiagnostics(): Map<String, Any> {
        return mapOf(
            "modelAvailable" to modelAvailable,
            "modelLoadError" to (modelLoadError ?: "None"),
            "modelAssetPath" to modelAssetPath,
            "inputShape" to inputShapeStr,
            "inputDataType" to (inputDataType?.toString() ?: "N/A"),
            "outputShape" to outputShapeStr,
            "outputDataType" to (outputDataType?.toString() ?: "N/A"),
            "threshold" to config.effectiveThreshold(),
            "requiredPositives" to config.requiredPositiveWindows,
            "confirmationWindows" to config.confirmationWindowCount,
            "noiseFloorRms" to noiseFloorRms,
            "lastWakeScore" to lastWakeScore,
            "lastWakeTimestamp" to lastWakeTimestamp,
            "detectionCount" to detectionCount
        )
    }

    companion object {
        private const val TAG = "DsCnnWakeWordDetector"
        const val NUM_TIME_FRAMES = 49
        const val NUM_MFCC = 10
        const val NUM_CLASSES = 3
        const val JARVIS_CLASS_INDEX = 2 // 0=silence, 1=unknown speech, 2=Jarvis
    }
}
