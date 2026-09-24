package com.jarvis.retrieval.eval.dataset

import com.jarvis.retrieval.eval.model.GroundTruthEntry
import com.jarvis.retrieval.router.RetrievalRoute

object RetrievalBenchmarkDataset {
    val SUITE: List<GroundTruthEntry> = listOf(
        GroundTruthEntry(
            queryId = "q_know_1",
            query = "RAG mein re-ranker kyu use karte hain?",
            expectedRoute = RetrievalRoute.RAG,
            relevantIds = setOf("doc_reranker_explanation", "doc_rag_precision"),
            irrelevantIds = setOf("doc_fruit_apple", "mem_user_music")
        ),
        GroundTruthEntry(
            queryId = "q_mem_1",
            query = "meri favourite playlist ka naam kya hai?",
            expectedRoute = RetrievalRoute.MAG,
            relevantIds = setOf("mem_fav_playlist"),
            irrelevantIds = setOf("doc_reranker_explanation")
        ),
        GroundTruthEntry(
            queryId = "q_cag_1",
            query = "battery status aur background apps batao",
            expectedRoute = RetrievalRoute.CAG,
            relevantIds = setOf("cag_ambient", "cag_default"),
            irrelevantIds = setOf("mem_fav_playlist")
        ),
        GroundTruthEntry(
            queryId = "q_ambig_1",
            query = "Raphael ke wake word system ko lightweight kaise banana tha?",
            expectedRoute = RetrievalRoute.HYBRID,
            relevantIds = setOf("doc_wake_word_opt", "mem_wake_word_pref"),
            irrelevantIds = setOf("doc_fruit_apple")
        ),
        GroundTruthEntry(
            queryId = "q_unanswerable_1",
            query = "What is the capital of Mars in 2050?",
            expectedRoute = RetrievalRoute.FAST_RAG,
            relevantIds = emptySet(),
            isUnanswerable = true
        )
    )
}
