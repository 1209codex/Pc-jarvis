package com.jarvis.retrieval.context

import com.jarvis.retrieval.model.CandidateSource
import com.jarvis.retrieval.model.EvaluatedCandidate

data class ContextBudget(
    val maxTokens: Int = 2000,
    val systemStatePct: Float = 0.15f,
    val conversationPct: Float = 0.25f,
    val memoryPct: Float = 0.20f,
    val knowledgePct: Float = 0.30f,
    val toolStatePct: Float = 0.10f
)

class ContextDeduplicator {
    fun deduplicate(candidates: List<EvaluatedCandidate>): List<EvaluatedCandidate> {
        val seenTexts = mutableSetOf<String>()
        return candidates.filter { item ->
            val norm = item.candidate.text.trim().lowercase()
            norm.isNotBlank() && seenTexts.add(norm)
        }
    }
}

class StructuredContextBuilder(
    private val budget: ContextBudget = ContextBudget(),
    private val deduplicator: ContextDeduplicator = ContextDeduplicator()
) {
    fun build(
        @Suppress("UNUSED_PARAMETER") query: String,
        candidates: List<EvaluatedCandidate>,
        systemState: String = "",
        activeTask: String = "",
        toolsAvailable: List<String> = emptyList()
    ): String {
        val dedupedCandidates = deduplicator.deduplicate(candidates)
        val sb = StringBuilder()

        if (systemState.isNotBlank()) {
            sb.append("[CURRENT SYSTEM STATE]\n").append(systemState).append("\n\n")
        }

        if (activeTask.isNotBlank()) {
            sb.append("[ACTIVE TASK]\n").append(activeTask).append("\n\n")
        }

        if (toolsAvailable.isNotEmpty()) {
            sb.append("[AVAILABLE TOOLS]\n").append(toolsAvailable.joinToString(", ")).append("\n\n")
        }

        val cagItems = dedupedCandidates.filter {
            it.candidate.source == CandidateSource.CONVERSATION_CAG ||
            it.candidate.source == CandidateSource.DEVICE_STATE_CAG
        }
        if (cagItems.isNotEmpty()) {
            sb.append("[RECENT CONVERSATION & SESSION CONTEXT]\n")
            cagItems.take(4).forEach { item ->
                sb.append("- ").append(item.candidate.text).append("\n")
            }
            sb.append("\n")
        }

        val memoryItems = dedupedCandidates.filter { it.candidate.source == CandidateSource.USER_MEMORY }
        if (memoryItems.isNotEmpty()) {
            sb.append("[RELEVANT USER MEMORY]\n")
            memoryItems.take(5).forEach { item ->
                sb.append("- ").append(item.candidate.text).append("\n")
            }
            sb.append("\n")
        }

        val knowledgeItems = dedupedCandidates.filter {
            it.candidate.source == CandidateSource.KNOWLEDGE_RAG ||
            it.candidate.source == CandidateSource.LEXICAL_EXACT
        }
        if (knowledgeItems.isNotEmpty()) {
            sb.append("[RELEVANT KNOWLEDGE]\n")
            knowledgeItems.take(6).forEach { item ->
                sb.append("- ").append(item.candidate.text).append("\n")
            }
            sb.append("\n")
        }

        return truncateToBudget(sb.toString().trim(), budget.maxTokens)
    }

    private fun truncateToBudget(text: String, maxTokens: Int): String {
        // Approximate 1 token = ~4 chars
        val maxChars = maxTokens * 4
        if (text.length <= maxChars) return text
        return text.substring(0, maxChars) + "\n...[Context truncated to budget]"
    }
}
