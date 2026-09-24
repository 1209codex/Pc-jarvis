package com.jarvis.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** On-device port of tflite-hub/conformer-speaker-encoder's sidlingvo pipeline. */
class TfliteOwnerVoiceVerifier(context: Context) : Closeable {
    private val vad: Interpreter?
    private val speaker: Interpreter?
    private val vadMean: FloatArray
    private val vadStd: FloatArray
    @Volatile private var failure: String? = null

    init {
        var vadModel: Interpreter? = null
        var speakerModel: Interpreter? = null
        var means = FloatArray(0)
        var stddev = FloatArray(0)
        try {
            vadModel = Interpreter(assetBuffer(context, "speaker/vad_long_model.tflite"), Interpreter.Options().setNumThreads(2))
            speakerModel = Interpreter(assetBuffer(context, "speaker/conformer_tisid_medium.tflite"), Interpreter.Options().setNumThreads(2))
            context.assets.open("speaker/vad_long_mean_stddev.csv").bufferedReader().use { reader ->
                val rows = reader.readLines().map { row -> row.split(',').map(String::trim).map(String::toFloat) }
                require(rows.size == 2 && rows.all { it.size == 128 }) { "Invalid VAD normalization data" }
                means = FloatArray(128) { rows[0][it] }
                stddev = FloatArray(128) { rows[1][it] }
                require(stddev.all { it.isFinite() && it > 0f }) { "Invalid VAD normalization standard deviations" }
            }
            require(vadModel.inputTensorCount == 1 && vadModel.outputTensorCount == 1)
            require(vadModel.getInputTensor(0).shape().contentEquals(intArrayOf(1, 528)))
            require(vadModel.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 4)))
            require(speakerModel.inputTensorCount == 89 && speakerModel.outputTensorCount == 89)
            require(speakerModel.getInputTensor(0).shape().contentEquals(intArrayOf(2, 512)))
            require(speakerModel.getOutputTensor(0).shape().contentEquals(intArrayOf(1, OwnerVoiceProfile.EMBEDDING_SIZE)))
        } catch (t: Throwable) {
            vadModel?.close(); speakerModel?.close()
            vadModel = null; speakerModel = null
            failure = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "Speaker encoder unavailable", t)
        }
        vad = vadModel
        speaker = speakerModel
        vadMean = means
        vadStd = stddev
    }

    fun isAvailable(): Boolean = vad != null && speaker != null && failure == null
    fun error(): String? = failure

    @Synchronized
    fun embed(pcm: ShortArray): FloatArray? {
        val vadModel = vad ?: return null
        val speakerModel = speaker ?: return null
        return try {
            val features = frontend(pcm) ?: return null
            vadModel.resetVariableTensors()
            val speech = ArrayList<FloatArray>()
            val vadIn = floatBuffer(528)
            val vadOut = floatBuffer(4)
            for (frame in features) {
                vadIn.clear()
                for (i in frame.indices) vadIn.putFloat(normalizeVadValue(frame[i], i, vadMean, vadStd))
                repeat(16) { cluster -> vadIn.putFloat(if (cluster == 2) 1f else 0f) }
                vadIn.flip(); vadOut.clear()
                vadModel.run(vadIn, vadOut)
                vadOut.rewind()
                if (exp(vadOut.float.toDouble()) > VAD_THRESHOLD) speech += frame
            }
            if (speech.size < 2) return null
            speakerModel.resetVariableTensors()
            val states = (1 until speakerModel.inputTensorCount).map { i -> zeroBuffer(speakerModel.getInputTensor(i).dataType(), speakerModel.getInputTensor(i).shape()) }
            val stateOutputs = (1 until speakerModel.outputTensorCount).map { i ->
                val outputTensor = speakerModel.getOutputTensor(i)
                zeroBuffer(outputTensor.dataType(), outputTensor.shape())
            }
            val inputArray = arrayOfNulls<Any>(speakerModel.inputTensorCount)
            val featureBuffer = floatBuffer(2 * 512)
            inputArray[0] = featureBuffer
            states.forEachIndexed { i, b -> inputArray[i + 1] = b }
            val vectorBuffer = floatBuffer(256)
            val outputMap = speakerOutputMap(vectorBuffer, stateOutputs)
            var last: FloatArray? = null
            var offset = 0
            while (offset + 1 < speech.size) {
                featureBuffer.clear()
                speech[offset].forEach(featureBuffer::putFloat)
                speech[offset + 1].forEach(featureBuffer::putFloat)
                featureBuffer.flip()
                states.forEach { it.clear() }
                vectorBuffer.clear(); stateOutputs.forEach { it.clear() }
                speakerModel.runForMultipleInputsOutputs(inputArray, outputMap)
                vectorBuffer.rewind()
                val vector = FloatArray(256) { vectorBuffer.float }
                if (vector.all { it.isFinite() }) last = vector else return null
                states.forEachIndexed { i, state ->
                    state.clear(); state.put(stateOutputs[i].apply { rewind() }); state.flip()
                }
                offset += 2
            }
            last
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Speaker encoder inference failed; rejecting candidate", t)
            null
        }
    }

    @Synchronized override fun close() { vad?.close(); speaker?.close() }

    companion object {
        private const val TAG = "OwnerVoiceVerifier"
        private const val VAD_THRESHOLD = 0.1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 513
        private const val FRAME_STEP = 160
        private const val FFT_SIZE = 1024
        private const val BINS = 128
        private val melWeights by lazy { makeMelWeights() }

        fun accepts(similarity: Float?, threshold: Float): Boolean =
            similarity != null && similarity.isFinite() && threshold.isFinite() && threshold in 0.5f..0.999f && similarity >= threshold

        internal fun speakerOutputMap(vector: Any, stateOutputs: List<Any>): Map<Int, Any> =
            buildMap(stateOutputs.size + 1) {
                put(0, vector)
                stateOutputs.forEachIndexed { i, output -> put(i + 1, output) }
            }

        internal fun normalizeVadValue(value: Float, featureIndex: Int, mean: FloatArray, stddev: FloatArray): Float =
            (value - mean[featureIndex / 4]) / stddev[featureIndex / 4]

        fun shouldWake(neuralScore: Float, kwsThreshold: Float, similarity: Float?, speakerThreshold: Float, modelsReady: Boolean, profileReady: Boolean): Boolean =
            modelsReady && profileReady && neuralScore.isFinite() && kwsThreshold.isFinite() &&
                neuralScore >= kwsThreshold && accepts(similarity, speakerThreshold)

        private fun assetBuffer(context: Context, path: String): ByteBuffer = context.assets.openFd(path).use { afd ->
            afd.createInputStream().channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }

        private fun floatBuffer(elements: Int): ByteBuffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())

        private fun zeroBuffer(type: DataType, shape: IntArray): ByteBuffer {
            val count = shape.fold(1) { a, b -> a * b }
            val bytes = when (type) { DataType.FLOAT32, DataType.INT32 -> 4; DataType.INT64 -> 8; DataType.BOOL, DataType.UINT8, DataType.INT8 -> 1; else -> error("Unsupported speaker state type $type") }
            return ByteBuffer.allocateDirect(count * bytes).order(ByteOrder.nativeOrder())
        }

        private fun frontend(pcm: ShortArray): List<FloatArray>? {
            if (pcm.size < FRAME_SIZE) return null
            val raw = ArrayList<FloatArray>()
            val noise = java.util.Random(0x4a6172766973L)
            for (start in 0..pcm.size - FRAME_SIZE step FRAME_STEP) {
                val real = DoubleArray(FFT_SIZE); val imag = DoubleArray(FFT_SIZE)
                var energy = 0.0
                for (i in 0 until FRAME_SIZE - 1) {
                    val x0 = pcm[start + i].toDouble()
                    val x1 = pcm[start + i + 1].toDouble()
                    val gaussianNoise = noise.nextGaussian() * 8.0
                    val preemphasized = x1 - 0.97 * x0 + gaussianNoise
                    val windowed = preemphasized * (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_SIZE - 1)))
                    real[i] = windowed
                    energy += windowed * windowed
                }
                if (energy <= 1e-8) continue
                fft(real, imag)
                val mel = FloatArray(BINS)
                for (b in 0 until BINS) {
                    var sum = 0.0
                    for (k in 0..FFT_SIZE / 2) {
                        val magnitude = sqrt(real[k] * real[k] + imag[k] * imag[k])
                        sum += magnitude * melWeights[k][b]
                    }
                    mel[b] = ln(max(1.0, sum)).toFloat()
                }
                raw += mel
            }
            if (raw.isEmpty()) return null
            val stacked = ArrayList<FloatArray>()
            for (offset in 0 until raw.size + 3 step 3) {
                if (offset + 3 >= raw.size + 3) break
                val joined = FloatArray(512)
                for (context in 0..3) {
                    val index = offset + context - 3
                    val row = raw[index.coerceAtLeast(0)]
                    row.copyInto(joined, context * BINS)
                }
                stacked += joined
            }
            return stacked
        }

        private fun makeMelWeights(): Array<DoubleArray> {
            val weights = Array(FFT_SIZE / 2 + 1) { DoubleArray(BINS) }
            fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
            fun hz(m: Double) = 700.0 * (kotlin.math.exp(m / 1127.0) - 1.0)
            val lo = mel(125.0); val hi = mel(7500.0)
            val points = DoubleArray(BINS + 2) { hz(lo + (hi - lo) * it / (BINS + 1)) }
            for (k in weights.indices) {
                val f = SAMPLE_RATE.toDouble() * k / FFT_SIZE
                for (b in 0 until BINS) {
                    val lower = points[b]; val center = points[b + 1]; val upper = points[b + 2]
                    weights[k][b] = max(0.0, min((f - lower) / (center - lower), (upper - f) / (upper - center)))
                }
            }
            return weights
        }

        private fun fft(real: DoubleArray, imag: DoubleArray) {
            var j = 0
            for (i in 1 until FFT_SIZE) {
                var bit = FFT_SIZE shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r; val v = imag[i]; imag[i] = imag[j]; imag[j] = v }
            }
            var len = 2
            while (len <= FFT_SIZE) {
                val theta = -2.0 * PI / len; val wr0 = cos(theta); val wi0 = sin(theta)
                for (base in 0 until FFT_SIZE step len) {
                    var wr = 1.0; var wi = 0.0
                    for (k in 0 until len / 2) {
                        val ar = real[base + k]; val ai = imag[base + k]
                        val br = real[base + k + len / 2] * wr - imag[base + k + len / 2] * wi
                        val bi = real[base + k + len / 2] * wi + imag[base + k + len / 2] * wr
                        real[base + k] = ar + br; imag[base + k] = ai + bi
                        real[base + k + len / 2] = ar - br; imag[base + k + len / 2] = ai - bi
                        val nr = wr * wr0 - wi * wi0
                        wi = wr * wi0 + wi * wr0; wr = nr
                    }
                }
                len = len shl 1
            }
        }
    }
}
