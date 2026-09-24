package com.jarvis.retrieval.router

import com.jarvis.retrieval.model.QueryIntent

enum class RetrievalRoute {
    NO_RETRIEVAL,
    FAST_RAG,
    RAG,
    MAG,
    CAG,
    HYBRID
}

class RetrievalRouter(val classifier: QueryClassifier = QueryClassifier()) {

    fun route(query: String, @Suppress("UNUSED_PARAMETER") stateContext: String = ""): RetrievalRoute {
        val intent = classifier.classify(query)
        return routeForIntent(intent)
    }

    fun routeForIntent(intent: QueryIntent): RetrievalRoute {
        return when (intent) {
            QueryIntent.COMMAND -> RetrievalRoute.NO_RETRIEVAL
            QueryIntent.MEMORY_QUERY, QueryIntent.PERSONAL_PREFERENCE_QUERY -> RetrievalRoute.MAG
            QueryIntent.CURRENT_CONTEXT_QUERY -> RetrievalRoute.CAG
            QueryIntent.KNOWLEDGE_QUERY -> RetrievalRoute.RAG
            QueryIntent.HISTORICAL_QUERY,
            QueryIntent.MULTI_STEP_TASK,
            QueryIntent.AMBIGUOUS_QUERY -> RetrievalRoute.HYBRID
            QueryIntent.CHAT,
            QueryIntent.QUESTION -> RetrievalRoute.FAST_RAG
        }
    }
}
