package com.jarvis.data.repository

import android.content.ContentValues
import android.database.Cursor
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.model.MemorySourceType
import com.jarvis.data.model.MemoryStatus
import com.jarvis.data.model.UnifiedMemoryRecord

class MemoryRepository(private val dbHelper: JarvisDatabase) {

    /**
     * Saves or supersedes a memory with temporal versioning.
     * If an active memory with the same (namespace, key) exists, it marks the previous
     * one as SUPERSEDED and links it to the newly inserted memory.
     */
    fun saveMemory(record: UnifiedMemoryRecord): Long {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Find existing active memory for same key and namespace
            var existingId: Long? = null
            var existingVersion = 0

            val query = "SELECT id, version FROM memories WHERE namespace = ? AND key = ? AND status = 'ACTIVE' LIMIT 1"
            db.rawQuery(query, arrayOf(record.namespace, record.key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(0)
                    existingVersion = cursor.getInt(1)
                }
            }

            // Insert new memory
            val values = ContentValues().apply {
                put("namespace", record.namespace)
                put("type", record.type)
                put("subject_id", record.subjectId)
                put("key", record.key)
                put("content", record.content)
                put("confidence", record.confidence)
                put("importance", record.importance)
                put("source_type", record.sourceType.name)
                put("source_id", record.sourceId)
                put("created_at", record.createdAt)
                put("updated_at", record.updatedAt)
                put("last_accessed_at", record.lastAccessedAt)
                put("expires_at", record.expiresAt)
                put("status", MemoryStatus.ACTIVE.name)
                put("version", existingVersion + 1)
            }
            val newId = db.insert("memories", null, values)

            // Mark old memory as SUPERSEDED
            if (existingId != null && newId != -1L) {
                val updateOld = ContentValues().apply {
                    put("status", MemoryStatus.SUPERSEDED.name)
                    put("superseded_by", newId)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("memories", updateOld, "id = ?", arrayOf(existingId.toString()))
            }

            db.setTransactionSuccessful()
            return newId
        } finally {
            db.endTransaction()
        }
    }

    fun getActiveMemories(namespace: String? = null, limit: Int = 100): List<UnifiedMemoryRecord> {
        val db = dbHelper.readableDatabase
        val whereClause = if (namespace != null) "status = 'ACTIVE' AND namespace = ?" else "status = 'ACTIVE'"
        val args = if (namespace != null) arrayOf(namespace) else null

        val results = mutableListOf<UnifiedMemoryRecord>()
        db.query(
            "memories",
            null,
            whereClause,
            args,
            null,
            null,
            "importance DESC, updated_at DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapCursorToRecord(cursor))
            }
        }
        return results
    }

    fun searchMemories(query: String, namespace: String? = null, limit: Int = 20): List<UnifiedMemoryRecord> {
        val db = dbHelper.readableDatabase
        val sql = buildString {
            append("SELECT * FROM memories WHERE status = 'ACTIVE' AND (content LIKE ? OR key LIKE ?)")
            if (namespace != null) append(" AND namespace = ?")
            append(" ORDER BY importance DESC, updated_at DESC LIMIT ?")
        }
        val wildcard = "%$query%"
        val args = if (namespace != null) arrayOf(wildcard, wildcard, namespace, limit.toString())
        else arrayOf(wildcard, wildcard, limit.toString())

        val results = mutableListOf<UnifiedMemoryRecord>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapCursorToRecord(cursor))
            }
        }
        return results
    }

    fun deleteMemory(id: Long): Boolean {
        val db = dbHelper.writableDatabase
        val rows = db.delete("memories", "id = ?", arrayOf(id.toString()))
        return rows > 0
    }

    private fun mapCursorToRecord(cursor: Cursor): UnifiedMemoryRecord {
        return UnifiedMemoryRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            namespace = cursor.getString(cursor.getColumnIndexOrThrow("namespace")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
            subjectId = cursor.getString(cursor.getColumnIndexOrThrow("subject_id")),
            key = cursor.getString(cursor.getColumnIndexOrThrow("key")),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
            importance = cursor.getFloat(cursor.getColumnIndexOrThrow("importance")),
            sourceType = runCatching {
                MemorySourceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("source_type")))
            }.getOrDefault(MemorySourceType.USER_EXPLICIT),
            sourceId = cursor.getString(cursor.getColumnIndexOrThrow("source_id")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            lastAccessedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_accessed_at")),
            expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at")),
            status = runCatching {
                MemoryStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
            }.getOrDefault(MemoryStatus.ACTIVE),
            version = cursor.getInt(cursor.getColumnIndexOrThrow("version")),
            supersededBy = cursor.getLong(cursor.getColumnIndexOrThrow("superseded_by"))
        )
    }
}
