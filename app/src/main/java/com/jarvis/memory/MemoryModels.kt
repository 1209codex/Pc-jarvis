package com.jarvis.memory

data class MemoryStats(
    val activeMemories: Int,
    val archivedMemories: Int,
    val conversations: Int,
    val documentChunks: Int,
    val preferences: Int
)

enum class MemoryType {
    SHORT_TERM,
    WORKING_MEMORY,
    USER_PREFERENCE,
    STRUCTURED_FACT,
    DOCUMENT_SNIPPET,
    TASK_ARTIFACT,
    LEARNED_PATTERN
}

data class MemoryItem(
    val id: Long = 0,
    val type: MemoryType,
    val key: String,
    val content: String,
    val provenance: String,
    val score: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val version: Int = 1,
    val importance: Double = 0.5,
    val accessCount: Long = 0,
    val lastAccessedAt: Long? = null,
    val expiresAt: Long? = null,
    val namespace: String = "default",
    val supersedesId: String? = null,
    val isConfirmed: Boolean = false
)

data class ConversationRecord(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

data class ContextPackage(
    val currentRequest: String,
    val workingMemory: Map<String, String>,
    val conversationHistorySummary: String,
    val relevantPreferences: List<MemoryItem>,
    val retrievedEvidence: List<MemoryItem>,
    val activeAppContext: String? = null
)

data class AugmentedContext(
    val cag: ContextPackage,
    val ragEvidence: List<MemoryItem>,
    val magMemories: List<MemoryItem>,
    val recentConversation: List<ConversationRecord>,
    val formatted: String
)
