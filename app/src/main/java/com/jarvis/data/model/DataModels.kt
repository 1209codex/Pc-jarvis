package com.jarvis.data.model

enum class MemoryStatus { ACTIVE, SUPERSEDED, ARCHIVED, EXPIRED }
enum class MemorySourceType { USER_EXPLICIT, CONVERSATION_INFERRED, SYSTEM_OBSERVED, SENSOR }

data class UnifiedMemoryRecord(
    val id: Long = 0,
    val namespace: String,
    val type: String,
    val subjectId: String? = null,
    val key: String,
    val content: String,
    val confidence: Float = 1.0f,
    val importance: Float = 0.5f,
    val sourceType: MemorySourceType = MemorySourceType.USER_EXPLICIT,
    val sourceId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long? = null,
    val expiresAt: Long? = null,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val version: Int = 1,
    val supersededBy: Long? = null
)

data class DocumentRecord(
    val id: String,
    val title: String,
    val sourceUri: String? = null,
    val mimeType: String? = null,
    val checksum: String,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class DocumentChunkRecord(
    val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val content: String,
    val tokenCount: Int = 0,
    val checksum: String,
    val metadataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class TaskRecord(
    val id: Long = 0,
    val goal: String,
    val status: String,
    val priority: Int = 0,
    val resultSummary: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

data class TaskAuditLog(
    val id: Long = 0,
    val taskId: Long?,
    val actionType: String,
    val riskLevel: String,
    val reason: String?,
    val status: String,
    val details: String?,
    val timestamp: Long = System.currentTimeMillis()
)
