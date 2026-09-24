package com.jarvis.retrieval.model

enum class QueryIntent {
    CHAT,
    COMMAND,
    QUESTION,
    MEMORY_QUERY,
    KNOWLEDGE_QUERY,
    CURRENT_CONTEXT_QUERY,
    MULTI_STEP_TASK,
    AMBIGUOUS_QUERY,
    PERSONAL_PREFERENCE_QUERY,
    HISTORICAL_QUERY
}

enum class CandidateSource {
    KNOWLEDGE_RAG,
    USER_MEMORY,
    CONVERSATION_CAG,
    DEVICE_STATE_CAG,
    LEXICAL_EXACT
}

data class CandidateMetadata(
    val id: String,
    val source: CandidateSource,
    val text: String,
    val denseScore: Double = 0.0,
    val lexicalScore: Double = 0.0,
    val recencyScore: Double = 1.0,
    val importanceScore: Double = 0.5,
    val sourcePriority: Double = 0.5,
    val memoryId: String? = null,
    val memoryVersion: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val isConfirmed: Boolean = false,
    val supersedesId: String? = null
)

data class EvaluatedCandidate(
    val candidate: CandidateMetadata,
    var rerankScore: Double = 0.0,
    var compositeScore: Double = 0.0
)

data class RetrievalEvent(
    val id: String,
    val query: String,
    val route: String,
    val candidateCount: Int,
    val rerankerUsed: Boolean,
    val finalCount: Int,
    val latencyMs: Long,
    val confidenceScore: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class DetailedRetrievalResult(
    val query: String,
    val intent: QueryIntent,
    val route: com.jarvis.retrieval.router.RetrievalRoute,
    val candidates: List<EvaluatedCandidate>,
    val rerankerUsed: Boolean,
    val latencyMs: Long,
    val structuredContext: String,
    val rerankerName: String = if (rerankerUsed) "Composite Semantic & BM25 Ranker" else "None"
)
