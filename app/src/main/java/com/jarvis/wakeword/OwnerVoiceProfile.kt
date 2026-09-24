package com.jarvis.wakeword

import kotlin.math.sqrt

/** A template of speaker-encoder vectors; raw enrollment PCM is never kept here. */
class OwnerVoiceProfile private constructor(
    private val template: FloatArray,
    val threshold: Float,
    val enrolledAt: Long
) {
    fun matches(embedding: FloatArray): Boolean = similarity(embedding)?.let { TfliteOwnerVoiceVerifier.accepts(it, threshold) } ?: false
    fun toFloatArray(): FloatArray = template.copyOf()

    fun similarity(embedding: FloatArray): Float? {
        if (embedding.size != template.size || embedding.isEmpty() || embedding.any { !it.isFinite() }) return null
        val norm = sqrt(embedding.sumOf { it.toDouble() * it })
        if (norm <= 1e-6 || !norm.isFinite()) return null
        return template.indices.sumOf { template[it].toDouble() * embedding[it] / norm }
            .takeIf { it.isFinite() }?.toFloat()
    }

    companion object {
        const val EMBEDDING_SIZE = 256
        const val DEFAULT_THRESHOLD = 0.80f

        fun fromEmbeddings(
            embeddings: List<FloatArray>,
            threshold: Float = DEFAULT_THRESHOLD,
            enrolledAt: Long = System.currentTimeMillis()
        ): OwnerVoiceProfile {
            require(embeddings.isNotEmpty()) { "At least one speaker embedding is required" }
            require(threshold in 0.5f..0.999f)
            require(embeddings.all { it.size == EMBEDDING_SIZE && it.all(Float::isFinite) }) { "Invalid speaker embedding" }
            val mean = DoubleArray(EMBEDDING_SIZE)
            embeddings.forEach { vector ->
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                require(norm > 1e-6 && norm.isFinite()) { "Invalid speaker embedding" }
                for (i in mean.indices) mean[i] += vector[i] / norm
            }
            val norm = sqrt(mean.sumOf { it * it })
            require(norm > 1e-6 && norm.isFinite()) { "Invalid speaker template" }
            return OwnerVoiceProfile(FloatArray(EMBEDDING_SIZE) { (mean[it] / norm).toFloat() }, threshold, enrolledAt)
        }

        fun fromTemplate(template: FloatArray, threshold: Float, enrolledAt: Long): OwnerVoiceProfile =
            fromEmbeddings(listOf(template), threshold, enrolledAt)
    }
}
