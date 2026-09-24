package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

class RagSkill : Skill {
    override val id: String = "rag_knowledge_vault"
    override val name: String = "RAG & Knowledge Vault Retrieval Skill"
    override val description: String = "Performs hybrid BM25 and vector semantic search across indexed on-device documents, personal notes, and knowledge manuals."
    override val triggers: List<String> = listOf(
        "knowledge", "knowledge vault", "search notes", "search documents",
        "find in documents", "find in notes", "retrieve", "rag", "index document",
        "index file", "index note", "vault search", "what do my notes say", "what does the doc say"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 1. Indexing actions
        if (lower.contains("index") || lower.contains("add to vault") || lower.contains("save to vault")) {
            val contentOrPath = lower.replace(Regex("\\b(index|document|file|note|into|to|vault|knowledge|please|add|save)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("RAG_INDEX", mapOf("content" to contentOrPath)),
                explanation = "Indexing content into Knowledge Vault"
            )
        }

        // 2. Retrieval query
        val cleanQuery = lower.replace(
            Regex("\\b(search|find|retrieve|in|my|documents|notes|knowledge|vault|doc|docs|about|for|what|does|say|tell|me)\\b", RegexOption.IGNORE_CASE),
            " "
        ).replace(Regex("\\s+"), " ").trim()

        val finalQuery = if (cleanQuery.isNotBlank()) cleanQuery else goal

        return SkillResult(
            handled = true,
            proposedAction = AgentAction("RAG_RETRIEVE", mapOf("query" to finalQuery, "limit" to "4")),
            explanation = "Retrieving semantic evidence from Knowledge Vault for: \"$finalQuery\""
        )
    }
}
