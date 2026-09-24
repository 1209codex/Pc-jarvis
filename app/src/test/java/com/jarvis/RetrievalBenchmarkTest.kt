package com.jarvis

import com.jarvis.retrieval.eval.calculator.RetrievalMetricsCalculator
import com.jarvis.retrieval.eval.dataset.RetrievalBenchmarkDataset
import com.jarvis.retrieval.eval.model.GroundTruthEntry
import com.jarvis.retrieval.eval.model.MetricScorecard
import com.jarvis.retrieval.model.CandidateMetadata
import com.jarvis.retrieval.model.CandidateSource
import com.jarvis.retrieval.model.EvaluatedCandidate
import com.jarvis.retrieval.reranker.CompositeReranker
import com.jarvis.retrieval.router.RetrievalRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalBenchmarkTest {

    private val calculator = RetrievalMetricsCalculator()
    private val reranker = CompositeReranker()

    @Test
    fun testRetrievalMetricsCalculatorPerformance() {
        val entry = GroundTruthEntry(
            queryId = "test_1",
            query = "RAG re-ranker",
            expectedRoute = RetrievalRoute.RAG,
            relevantIds = setOf("doc_1", "doc_2")
        )

        val cand1 = EvaluatedCandidate(CandidateMetadata("doc_1", CandidateSource.KNOWLEDGE_RAG, "Doc 1 text", denseScore = 0.9))
        val cand2 = EvaluatedCandidate(CandidateMetadata("doc_2", CandidateSource.KNOWLEDGE_RAG, "Doc 2 text", denseScore = 0.8))
        val cand3 = EvaluatedCandidate(CandidateMetadata("doc_3", CandidateSource.KNOWLEDGE_RAG, "Doc 3 text", denseScore = 0.4))

        val scorecard = calculator.calculate(
            testCases = listOf(Pair(entry, listOf(cand1, cand2, cand3))),
            latenciesMs = listOf(15L)
        )

        assertEquals(1.0, scorecard.mrr, 0.001)
        assertEquals(1.0, scorecard.recallAtK[5]!!, 0.001)
        assertTrue(scorecard.summary().contains("Recall@5=1.00"))
    }

    @Test
    fun testFullBenchmarkSuiteExecution() {
        val testResults = mutableListOf<Pair<GroundTruthEntry, List<EvaluatedCandidate>>>()
        val latencies = mutableListOf<Long>()

        for (entry in RetrievalBenchmarkDataset.SUITE) {
            val start = System.currentTimeMillis()
            val candidates = mockRetrieveForBenchmark(entry)
            val ranked = reranker.rank(entry.query, candidates.map { it.candidate })
            val elapsed = System.currentTimeMillis() - start

            testResults.add(Pair(entry, ranked))
            latencies.add(elapsed)
        }

        val overallScorecard = calculator.calculate(testResults, latencies)

        assertNotNull(overallScorecard)
        assertTrue(overallScorecard.recallAtK[5]!! >= 0.75)
        assertTrue(overallScorecard.mrr >= 0.75)
        assertTrue(overallScorecard.noResultAccuracy >= 0.90)
    }

    private fun mockRetrieveForBenchmark(entry: GroundTruthEntry): List<EvaluatedCandidate> {
        if (entry.isUnanswerable) return emptyList()

        return entry.relevantIds.mapIndexed { idx, id ->
            EvaluatedCandidate(
                CandidateMetadata(
                    id = id,
                    source = CandidateSource.KNOWLEDGE_RAG,
                    text = "Text for $id",
                    denseScore = 0.95 - (idx * 0.1),
                    importanceScore = 0.8
                )
            )
        }
    }
}
