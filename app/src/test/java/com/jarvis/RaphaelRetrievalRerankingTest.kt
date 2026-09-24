package com.jarvis

import com.jarvis.retrieval.context.ContextDeduplicator
import com.jarvis.retrieval.context.StructuredContextBuilder
import com.jarvis.retrieval.engine.CandidateFusionEngine
import com.jarvis.retrieval.engine.RaphaelRetrievalManager
import com.jarvis.retrieval.model.CandidateMetadata
import com.jarvis.retrieval.model.CandidateSource
import com.jarvis.retrieval.model.EvaluatedCandidate
import com.jarvis.retrieval.model.QueryIntent
import com.jarvis.retrieval.reranker.CompositeReranker
import com.jarvis.retrieval.reranker.RerankerRouter
import com.jarvis.retrieval.router.QueryClassifier
import com.jarvis.retrieval.router.RetrievalRoute
import com.jarvis.retrieval.router.RetrievalRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaphaelRetrievalRerankingTest {

    @Test
    fun testQueryClassifierIntents() {
        val classifier = QueryClassifier()

        assertEquals(QueryIntent.COMMAND, classifier.classify("youtube kholo"))
        assertEquals(QueryIntent.COMMAND, classifier.classify("open spotify"))
        assertEquals(QueryIntent.MEMORY_QUERY, classifier.classify("meri favourite playlist ka naam kya hai?"))
        assertEquals(QueryIntent.CURRENT_CONTEXT_QUERY, classifier.classify("battery level kya hai?"))
        assertEquals(QueryIntent.KNOWLEDGE_QUERY, classifier.classify("RAG mein re-ranker kyu use karte hain?"))
    }

    @Test
    fun testRetrievalRouterRoutes() {
        val router = RetrievalRouter()

        assertEquals(RetrievalRoute.NO_RETRIEVAL, router.route("youtube kholo"))
        assertEquals(RetrievalRoute.MAG, router.route("meri favourite playlist ka naam kya hai?"))
        assertEquals(RetrievalRoute.CAG, router.route("battery level kya hai?"))
        assertEquals(RetrievalRoute.RAG, router.route("RAG mein re-ranker kyu use karte hain?"))
    }

    @Test
    fun testCandidateFusionEngine() {
        val fusionEngine = CandidateFusionEngine()

        val rag1 = CandidateMetadata("doc_1", CandidateSource.KNOWLEDGE_RAG, "RAG Architecture", denseScore = 0.8)
        val rag2 = CandidateMetadata("doc_2", CandidateSource.KNOWLEDGE_RAG, "Vector Indexing", denseScore = 0.7)
        val mag1 = CandidateMetadata("mem_1", CandidateSource.USER_MEMORY, "User prefers local models", denseScore = 0.9)
        val cag1 = CandidateMetadata("cag_1", CandidateSource.DEVICE_STATE_CAG, "Active App: Spotify", denseScore = 1.0)

        val fused = fusionEngine.fuse(
            ragCandidates = listOf(rag1, rag2),
            magCandidates = listOf(mag1),
            cagCandidates = listOf(cag1)
        )

        assertEquals(4, fused.size)
    }

    @Test
    fun testCompositeReranker() {
        val reranker = CompositeReranker()

        val cand1 = CandidateMetadata("1", CandidateSource.KNOWLEDGE_RAG, "High dense score", denseScore = 0.9, importanceScore = 0.9)
        val cand2 = CandidateMetadata("2", CandidateSource.KNOWLEDGE_RAG, "Low dense score", denseScore = 0.2, importanceScore = 0.1)

        val ranked = reranker.rank("query", listOf(cand2, cand1))

        assertEquals(2, ranked.size)
        assertEquals("1", ranked.first().candidate.id)
        assertTrue(ranked.first().compositeScore > ranked.last().compositeScore)
    }

    @Test
    fun testContextDeduplicator() {
        val deduplicator = ContextDeduplicator()

        val eval1 = EvaluatedCandidate(CandidateMetadata("1", CandidateSource.KNOWLEDGE_RAG, "Duplicate text here"))
        val eval2 = EvaluatedCandidate(CandidateMetadata("2", CandidateSource.KNOWLEDGE_RAG, "Duplicate text here"))
        val eval3 = EvaluatedCandidate(CandidateMetadata("3", CandidateSource.KNOWLEDGE_RAG, "Unique text content"))

        val result = deduplicator.deduplicate(listOf(eval1, eval2, eval3))

        assertEquals(2, result.size)
    }

    @Test
    fun testRaphaelRetrievalManagerNoRetrievalForCommand() {
        val manager = RaphaelRetrievalManager()
        val context = manager.retrieve("youtube kholo")

        assertEquals("", context)
    }

    @Test
    fun testRaphaelRetrievalManagerBuildsContext() {
        val manager = RaphaelRetrievalManager()
        val context = manager.retrieve("battery status aur background apps batao")

        assertTrue(context.contains("CURRENT SYSTEM STATE") || context.contains("RECENT CONVERSATION"))
    }
}
