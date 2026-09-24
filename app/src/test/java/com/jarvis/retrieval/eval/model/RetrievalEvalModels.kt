package com.jarvis.retrieval.eval.model

import com.jarvis.retrieval.router.RetrievalRoute

data class GroundTruthEntry(
    val queryId: String,
    val query: String,
    val expectedRoute: RetrievalRoute,
    val relevantIds: Set<String>,
    val irrelevantIds: Set<String> = emptySet(),
    val expectedConfidenceThreshold: Double = 0.5,
    val isUnanswerable: Boolean = false
)

data class MetricScorecard(
    val recallAtK: Map<Int, Double>,
    val precisionAtK: Map<Int, Double>,
    val mrr: Double,
    val ndcgAtK: Map<Int, Double>,
    val hitRateAtK: Map<Int, Double>,
    val noResultAccuracy: Double,
    val averageLatencyMs: Double,
    val rerankerOverheadMs: Double = 0.0
) {
    fun summary(): String {
        val r5 = "%.2f".format(recallAtK[5] ?: 0.0)
        val p5 = "%.2f".format(precisionAtK[5] ?: 0.0)
        val mrrFormatted = "%.2f".format(mrr)
        val ndcg5 = "%.2f".format(ndcgAtK[5] ?: 0.0)
        val noRes = "%.2f".format(noResultAccuracy)
        val lat = "%.1f".format(averageLatencyMs)
        return "Recall@5=$r5 Precision@5=$p5 MRR=$mrrFormatted NDCG@5=$ndcg5 NoResultAcc=$noRes AvgLatency=${lat}ms"
    }
}

data class PipelineBenchmarkReport(
    val pipelineName: String,
    val totalQueries: Int,
    val overallScorecard: MetricScorecard
)
