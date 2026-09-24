package com.jarvis.retrieval.eval.calculator

import com.jarvis.retrieval.eval.model.GroundTruthEntry
import com.jarvis.retrieval.eval.model.MetricScorecard
import com.jarvis.retrieval.model.EvaluatedCandidate
import kotlin.math.log2

class RetrievalMetricsCalculator {

    fun calculate(
        testCases: List<Pair<GroundTruthEntry, List<EvaluatedCandidate>>>,
        latenciesMs: List<Long>
    ): MetricScorecard {
        val kValues = listOf(1, 3, 5, 10)
        val recallSum = kValues.associateWith { 0.0 }.toMutableMap()
        val precisionSum = kValues.associateWith { 0.0 }.toMutableMap()
        val hitRateSum = kValues.associateWith { 0.0 }.toMutableMap()
        val ndcgSum = kValues.associateWith { 0.0 }.toMutableMap()
        var mrrSum = 0.0

        var noResultCorrect = 0
        var noResultTotal = 0

        for ((entry, candidates) in testCases) {
            val candidateIds = candidates.map { it.candidate.id }

            if (entry.isUnanswerable) {
                noResultTotal++
                val topScore = candidates.firstOrNull()?.compositeScore ?: 0.0
                if (topScore < entry.expectedConfidenceThreshold || candidates.isEmpty()) {
                    noResultCorrect++
                }
                continue
            }

            val relSet = entry.relevantIds
            if (relSet.isEmpty()) continue

            // MRR
            val firstRelIndex = candidateIds.indexOfFirst { relSet.contains(it) }
            if (firstRelIndex != -1) {
                mrrSum += 1.0 / (firstRelIndex + 1)
            }

            for (k in kValues) {
                val topK = candidateIds.take(k)
                val hits = topK.count { relSet.contains(it) }

                recallSum[k] = recallSum[k]!! + (hits.toDouble() / relSet.size)
                precisionSum[k] = precisionSum[k]!! + (hits.toDouble() / k)
                hitRateSum[k] = hitRateSum[k]!! + (if (hits > 0) 1.0 else 0.0)

                // DCG & IDCG for NDCG
                var dcg = 0.0
                topK.forEachIndexed { i, id ->
                    if (relSet.contains(id)) {
                        dcg += 1.0 / log2((i + 2).toDouble())
                    }
                }
                var idcg = 0.0
                repeat(minOf(k, relSet.size)) { i ->
                    idcg += 1.0 / log2((i + 2).toDouble())
                }
                val ndcg = if (idcg > 0) dcg / idcg else 0.0
                ndcgSum[k] = ndcgSum[k]!! + ndcg
            }
        }

        val n = testCases.count { !it.first.isUnanswerable }.coerceAtLeast(1)
        val recallAtK = recallSum.mapValues { it.value / n }
        val precisionAtK = precisionSum.mapValues { it.value / n }
        val hitRateAtK = hitRateSum.mapValues { it.value / n }
        val ndcgAtK = ndcgSum.mapValues { it.value / n }
        val mrr = mrrSum / n
        val noResultAcc = if (noResultTotal > 0) noResultCorrect.toDouble() / noResultTotal else 1.0
        val avgLatency = if (latenciesMs.isNotEmpty()) latenciesMs.average() else 0.0

        return MetricScorecard(
            recallAtK = recallAtK,
            precisionAtK = precisionAtK,
            mrr = mrr,
            ndcgAtK = ndcgAtK,
            hitRateAtK = hitRateAtK,
            noResultAccuracy = noResultAcc,
            averageLatencyMs = avgLatency,
            rerankerOverheadMs = 0.0
        )
    }
}
