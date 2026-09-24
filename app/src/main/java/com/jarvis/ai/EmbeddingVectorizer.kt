package com.jarvis.ai

import java.util.Locale
import kotlin.math.sqrt

/**
 * Local, deterministic dense embeddings computed from character n-grams.
 *
 * No embedding API is required: a fixed random projection of hashed tokens
 * produces a stable vector per text. Word stems surface through overlapping
 * 3-4 char n-grams (exercise ~ exercises), which pure token retrieval misses.
 * Not semantic in the LLM sense, so callers treat missing cosine signal as
 * "no match" and fail closed to keyword retrieval.
 */
class EmbeddingVectorizer(private val dims: Int = 256) {

    fun embed(text: String): FloatArray {
        val vec = FloatArray(dims)
        for (word in words(text)) {
            for (piece in pieces(word)) {
                val hash = piece.hashCode() and 0x7fffffff
                val idx = hash % dims
                val sign = if (hash and 1 == 1) 1f else -1f
                vec[idx] += sign
            }
        }
        val norm = sqrt(vec.fold(0.0) { acc, v -> acc + v * v }).coerceAtLeast(1e-6)
        for (i in vec.indices) vec[i] = (vec[i] / norm).toFloat()
        return vec
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return (dot / sqrt(na * nb)).coerceIn(0.0, 1.0)
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

    private fun pieces(word: String): List<String> = buildList {
        add(word)
        for (n in 3..4) {
            if (word.length >= n) addAll(word.windowed(n))
        }
    }
}