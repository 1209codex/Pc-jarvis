package com.jarvis.retrieval.router

import com.jarvis.retrieval.model.QueryIntent
import java.util.Locale

class QueryClassifier {
    fun classify(query: String): QueryIntent {
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return QueryIntent.CHAT

        // Direct command / action check (e.g. "youtube kholo", "open spotify")
        if (isCommand(lower)) return QueryIntent.COMMAND

        // Ambient / Device / Context check (battery, screen, active playing app) - higher priority than general "kya hai"
        if (isContextQuery(lower)) return QueryIntent.CURRENT_CONTEXT_QUERY

        // Personal memory & preference check
        if (isMemoryQuery(lower)) return QueryIntent.MEMORY_QUERY
        if (isPreferenceQuery(lower)) return QueryIntent.PERSONAL_PREFERENCE_QUERY

        // Historical conversation / past decision check
        if (isHistoricalQuery(lower)) return QueryIntent.HISTORICAL_QUERY

        // Document / Knowledge check
        if (isKnowledgeQuery(lower)) return QueryIntent.KNOWLEDGE_QUERY

        // Multi-step task or ambiguous query checks
        if (isMultiStep(lower)) return QueryIntent.MULTI_STEP_TASK
        if (isAmbiguous(lower)) return QueryIntent.AMBIGUOUS_QUERY

        return QueryIntent.QUESTION
    }

    private fun isCommand(q: String): Boolean {
        return q.contains("kholo") || q.startsWith("open ") || q.contains("open ") ||
               q.startsWith("call ") || q.contains("set timer") || q.contains("turn on") ||
               q.contains("turn off") || q.startsWith("play ") || q.contains("baja") ||
               q.startsWith("send ") || q.contains("bhejo") || q.startsWith("launch ")
    }

    private fun isMemoryQuery(q: String): Boolean {
        return q.contains("yaad") || q.contains("remember") || q.contains("maine kya bola") ||
               q.contains("my favorite") || q.contains("mera favorite") || q.contains("favorite artist") ||
               q.contains("favourite")
    }

    private fun isPreferenceQuery(q: String): Boolean {
        return q.contains("pasand") || q.contains("preference") || q.contains("like most") ||
               q.contains("hamesha") || q.contains("default option")
    }

    private fun isContextQuery(q: String): Boolean {
        return q.contains("battery") || q.contains("status") || q.contains("notification") ||
               q.contains("kya chal") || q.contains("abhibhi") || q.contains("screen pe") ||
               q.contains("playing now") || q.contains("current app")
    }

    private fun isHistoricalQuery(q: String): Boolean {
        return q.contains("kal ") || q.contains("yesterday") || q.contains("last time") ||
               q.contains("pehle decision") || q.contains("previous discussion") ||
               q.contains("humne kya decide किया") || q.contains("decide kiya था") ||
               q.contains("jo humne")
    }

    private fun isKnowledgeQuery(q: String): Boolean {
        return q.contains("kya hai") || q.contains("what is") || q.contains("how to") ||
               q.contains("doc") || q.contains("manual") || q.contains("specs") ||
               q.contains("architecture") || q.contains("guide") || q.contains("pdf") ||
               q.contains("vault") || q.contains("rag mein") || q.contains("rag") ||
               q.contains("re-ranker") || q.contains("reranker")
    }

    private fun isMultiStep(q: String): Boolean {
        return (q.contains("aur ") && q.contains("phir ")) || q.contains("step by step") ||
               q.contains("and then") || q.contains("after that")
    }

    private fun isAmbiguous(q: String): Boolean {
        return q.split(" ").size < 3 && !q.contains("kya") && !q.contains("what")
    }
}
