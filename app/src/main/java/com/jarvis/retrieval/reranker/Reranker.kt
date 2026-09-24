package com.jarvis.retrieval.reranker

import com.jarvis.retrieval.model.CandidateMetadata
import com.jarvis.retrieval.model.EvaluatedCandidate

class CompositeReranker {
    val name: String = "Composite Semantic & BM25 Ranker"

    fun rank(query: String, candidates: List<CandidateMetadata>): List<EvaluatedCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val cleanQuery = query.lowercase().trim()
        val queryTokens = cleanQuery.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

        return candidates.map { c ->
            val daysOld = ((now - c.timestamp) / (1000.0 * 60 * 60 * 24)).coerceAtLeast(0.0)
            val recencyDecay = 1.0 / (1.0 + 0.05 * daysOld)

            val textLower = c.text.lowercase()
            val phraseBonus = if (cleanQuery.isNotEmpty() && textLower.contains(cleanQuery)) 0.15 else 0.0
            val tokenOverlapBonus = if (queryTokens.isNotEmpty()) {
                val candidateTokens = textLower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
                (queryTokens.intersect(candidateTokens).size.toDouble() / queryTokens.size.coerceAtLeast(1)) * 0.10
            } else 0.0

            val effectiveLexical = (c.lexicalScore + phraseBonus + tokenOverlapBonus).coerceIn(0.0, 1.0)

            // Multi-factor relevance scoring formula
            val score = (0.35 * c.denseScore) +
                        (0.25 * effectiveLexical) +
                        (0.15 * recencyDecay) +
                        (0.15 * c.importanceScore) +
                        (0.10 * c.sourcePriority)

            EvaluatedCandidate(
                candidate = c,
                rerankScore = score,
                compositeScore = score
            )
        }.sortedByDescending { it.compositeScore }
    }
}

class RerankerRouter(
    private val localReranker: CompositeReranker = CompositeReranker()
) {
    fun getActiveRerankerName(): String = localReranker.name

    fun shouldRerank(
        @Suppress("UNUSED_PARAMETER") query: String,
        candidates: List<CandidateMetadata>,
        confidence: Double = 1.0,
        complexity: Double = 0.0
    ): Boolean {
        if (candidates.isEmpty()) return false
        return complexity >= 0.7 || confidence < 0.65 || candidates.size >= 15
    }

    fun rankCandidates(query: String, candidates: List<CandidateMetadata>): List<EvaluatedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        return localReranker.rank(query, candidates)
    }
}
