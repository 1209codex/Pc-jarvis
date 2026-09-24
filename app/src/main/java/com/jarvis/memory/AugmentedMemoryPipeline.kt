package com.jarvis.memory

import com.jarvis.ai.Message

/**
 * RAG + CAG + MAG orchestration.
 *
 * CAG (Context-Augmented Generation): current request + session history +
 * working state + active app.
 * RAG (Retrieval-Augmented Generation): relevant local knowledge/doc/task data.
 * MAG (Memory-Augmented Generation): durable preferences/facts + recent
 * conversations, plus a write path for future turns.
 */
class AugmentedMemoryPipeline(private val memoryStore: MemoryStore) {

    fun build(
        currentRequest: String,
        history: List<Message>,
        workingMemory: Map<String, String> = emptyMap(),
        activeApp: String? = null,
        sessionId: String = "default",
        ragLimit: Int = 6,
        magLimit: Int = 6
    ): AugmentedContext {
        val preferences = memoryStore.getPreferences(limit = 8)
        val ragEvidence = memoryStore.queryRelevantMemories(
            query = currentRequest,
            types = setOf(
                MemoryType.STRUCTURED_FACT,
                MemoryType.DOCUMENT_SNIPPET,
                MemoryType.TASK_ARTIFACT,
                MemoryType.LEARNED_PATTERN
            ),
            limit = ragLimit,
            namespace = "all"
        )
        val magMemories = memoryStore.queryRelevantMemories(
            query = currentRequest,
            types = setOf(
                MemoryType.USER_PREFERENCE,
                MemoryType.STRUCTURED_FACT,
                MemoryType.LEARNED_PATTERN
            ),
            limit = magLimit,
            namespace = "all"
        )

        val recent = memoryStore.getRecentConversation(sessionId, 8)
        val historyForCag = history.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
        val persistedHistory = recent.joinToString("\n") { "${it.role}: ${it.content}" }
        val summary = listOf(historyForCag, persistedHistory)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeLast(5000)

        val cag = ContextPackage(
            currentRequest = currentRequest,
            workingMemory = workingMemory,
            conversationHistorySummary = summary,
            relevantPreferences = preferences,
            retrievedEvidence = ragEvidence,
            activeAppContext = activeApp
        )

        return AugmentedContext(
            cag = cag,
            ragEvidence = ragEvidence,
            magMemories = magMemories,
            recentConversation = recent,
            formatted = format(cag, ragEvidence, magMemories, recent)
        )
    }

    fun rememberUserTurn(sessionId: String, text: String) {
        memoryStore.saveConversation(sessionId, "user", text)
    }

    fun rememberAssistantTurn(sessionId: String, text: String) {
        memoryStore.saveConversation(sessionId, "assistant", text)
    }

    fun rememberFact(
        key: String,
        value: String,
        provenance: String = "user",
        importance: Double = 0.75
    ): Long = memoryStore.saveMemory(
        type = MemoryType.USER_PREFERENCE,
        key = key,
        content = value,
        provenance = provenance,
        importance = importance
    )

    fun retrieveMemory(query: String, limit: Int = 8): List<MemoryItem> =
        memoryStore.queryRelevantMemories(
            query = query,
            types = MemoryType.values().toSet(),
            limit = limit
        )

    /** MAG — long-term user memory/preferences (personalization). */
    fun retrieveMag(query: String, limit: Int = 8): List<MemoryItem> =
        memoryStore.queryRelevantMemories(
            query = query,
            types = setOf(
                MemoryType.USER_PREFERENCE,
                MemoryType.STRUCTURED_FACT,
                MemoryType.LEARNED_PATTERN,
                MemoryType.SHORT_TERM
            ),
            limit = limit
        )

    /** RAG — knowledge/documents/task artifacts (retrieval-augmented knowledge). */
    fun retrieveRag(query: String, limit: Int = 8): List<MemoryItem> =
        memoryStore.queryRelevantMemories(
            query = query,
            types = setOf(
                MemoryType.DOCUMENT_SNIPPET,
                MemoryType.TASK_ARTIFACT,
                MemoryType.WORKING_MEMORY
            ),
            limit = limit
        )

    private fun format(
        cag: ContextPackage,
        rag: List<MemoryItem>,
        mag: List<MemoryItem>,
        recent: List<ConversationRecord>
    ): String {
        val sb = StringBuilder()

        sb.append("=== CAG: CURRENT CONTEXT ===\n")
        sb.append("Current request: ${cag.currentRequest}\n")
        if (cag.activeAppContext != null) sb.append("Active app: ${cag.activeAppContext}\n")
        if (cag.workingMemory.isNotEmpty()) {
            cag.workingMemory.forEach { (k, v) -> sb.append("Working state: $k=$v\n") }
        }
        if (cag.conversationHistorySummary.isNotBlank()) {
            sb.append("Recent dialogue:\n${cag.conversationHistorySummary}\n")
        }

        if (rag.isNotEmpty()) {
            sb.append("\n=== RAG: RETRIEVED LOCAL EVIDENCE ===\n")
            rag.forEachIndexed { index, item ->
                sb.append("[$index] ${item.key}: ${item.content} | source=${item.provenance} | score=${"%.2f".format(item.score)}\n")
            }
        }

        if (mag.isNotEmpty()) {
            sb.append("\n=== MAG: LONG-TERM MEMORY ===\n")
            mag.forEach { item ->
                sb.append("- ${item.key}: ${item.content} | source=${item.provenance}\n")
            }
        }

        if (recent.isNotEmpty()) {
            sb.append("\n=== MAG: PERSISTED CONVERSATION ===\n")
            recent.takeLast(6).forEach { item ->
                sb.append("- ${item.role}: ${item.content}\n")
            }
        }

        return sb.toString().trim()
    }
}
