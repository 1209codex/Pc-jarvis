package com.jarvis.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarvis.foundation.SensitiveTerms
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

interface IMemoryStore {
    fun queryByKey(key: String, namespace: String = "default"): List<MemoryItem>
    fun getAllActiveMemories(namespace: String = "default"): List<MemoryItem>
    fun archiveMemory(id: Long)
    fun saveMemoryItem(item: MemoryItem): Long
    fun rebuildFtsIndex()
    fun setConfirmed(id: Long, confirmed: Boolean) {}
}

/**
 * Single source of truth for Jarvis local memory.
 *
 * RAG here is intentionally hybrid: SQLite FTS4 keyword retrieval + local
 * lexical/recency/importance scoring. This is real retrieval-augmented
 * generation, but NOT vector-semantic retrieval. A semantic embedding provider
 * can be added later without changing the database contract.
 */
class MemoryDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                key TEXT NOT NULL,
                content TEXT NOT NULL,
                provenance TEXT NOT NULL,
                score REAL NOT NULL DEFAULT 1.0,
                created_at INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 1,
                importance REAL NOT NULL DEFAULT 0.5,
                access_count INTEGER NOT NULL DEFAULT 0,
                last_accessed_at INTEGER,
                expires_at INTEGER,
                namespace TEXT NOT NULL DEFAULT 'default',
                supersedes_id TEXT,
                confirmed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_type_archived ON memory_items(type, archived)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_created ON memory_items(created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_namespace ON memory_items(namespace)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_session_time ON conversation_messages(session_id, created_at DESC)")

        // FTS4 is used instead of a third-party vector database so the app keeps
        // its database fully local and lightweight on Android API 28+.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts4(
                memory_id UNINDEXED,
                key,
                content,
                provenance
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop user memory during normal app upgrades.
        createCoreTables(db)
        addColumnIfMissing(db, "memory_items", "importance", "REAL NOT NULL DEFAULT 0.5")
        addColumnIfMissing(db, "memory_items", "access_count", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "memory_items", "last_accessed_at", "INTEGER")
        addColumnIfMissing(db, "memory_items", "expires_at", "INTEGER")
        addColumnIfMissing(db, "memory_items", "namespace", "TEXT NOT NULL DEFAULT 'default'")
        addColumnIfMissing(db, "memory_items", "supersedes_id", "TEXT")
        addColumnIfMissing(db, "memory_items", "confirmed", "INTEGER NOT NULL DEFAULT 0")
        rebuildFts(db)
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        var exists = false
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(1).equals(column, ignoreCase = true)) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    fun rebuildFts(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM memory_fts")
            db.rawQuery(
                "SELECT id, key, content, provenance FROM memory_items WHERE archived = 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val values = ContentValues().apply {
                        put("memory_id", c.getLong(0).toString())
                        put("key", c.getString(1))
                        put("content", c.getString(2))
                        put("provenance", c.getString(3))
                    }
                    db.insert("memory_fts", null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "jarvis_memory.db"
        const val DATABASE_VERSION = 4
    }
}

class MemoryStore(context: Context) : IMemoryStore {
    private val dbHelper = MemoryDatabaseHelper(com.jarvis.storage.JarvisStorageHub.createDatabaseContext(context.applicationContext))

    fun saveMemory(
        type: MemoryType,
        key: String,
        content: String,
        provenance: String,
        score: Double = 1.0,
        importance: Double = 0.5,
        namespace: String = "default",
        ttlMs: Long? = null
    ): Long {
        val cleanKey = key.trim()
        val cleanContent = content.trim()
        if (cleanKey.isBlank() || cleanContent.isBlank()) return -1L
        if (SensitiveTerms.contains(cleanKey) || SensitiveTerms.contains(cleanContent)) return -2L

        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("type", type.name)
            put("key", cleanKey)
            put("content", cleanContent)
            put("provenance", provenance)
            put("score", score.coerceIn(0.0, 1.0))
            put("created_at", now)
            put("archived", 0)
            put("version", 1)
            put("importance", importance.coerceIn(0.0, 1.0))
            put("access_count", 0)
            putNull("last_accessed_at")
            if (ttlMs != null && ttlMs > 0) put("expires_at", now + ttlMs) else putNull("expires_at")
            put("namespace", namespace.ifBlank { "default" })
        }

        db.beginTransaction()
        return try {
            val id = db.insertOrThrow("memory_items", null, values)
            val ftsValues = ContentValues().apply {
                put("memory_id", id.toString())
                put("key", cleanKey)
                put("content", cleanContent)
                put("provenance", provenance)
            }
            db.insert("memory_fts", null, ftsValues)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    override fun archiveMemory(id: Long) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "memory_items",
                ContentValues().apply { put("archived", 1) },
                "id = ?",
                arrayOf(id.toString())
            )
            db.delete("memory_fts", "memory_id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun queryByKey(key: String, namespace: String): List<MemoryItem> {
        val db = dbHelper.readableDatabase
        val isAll = namespace.equals("all", ignoreCase = true) || namespace.isBlank()
        val selection = if (isAll) "key = ? AND archived = 0" else "key = ? AND namespace = ? AND archived = 0"
        val args = if (isAll) arrayOf(key) else arrayOf(key, namespace)
        val cursor = db.query(
            "memory_items",
            null,
            selection,
            args,
            null,
            null,
            "version DESC"
        )
        return cursorToItems(cursor)
    }

    override fun getAllActiveMemories(namespace: String): List<MemoryItem> {
        val db = dbHelper.readableDatabase
        val isAll = namespace.equals("all", ignoreCase = true) || namespace.isBlank()
        val selection = if (isAll) "archived = 0" else "namespace = ? AND archived = 0"
        val args = if (isAll) null else arrayOf(namespace)
        val cursor = db.query(
            "memory_items",
            null,
            selection,
            args,
            null,
            null,
            "created_at DESC"
        )
        return cursorToItems(cursor)
    }

    override fun saveMemoryItem(item: MemoryItem): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("type", item.type.name)
            put("key", item.key)
            put("content", item.content)
            put("provenance", item.provenance)
            put("score", item.score)
            put("created_at", item.createdAt)
            put("archived", if (item.archived) 1 else 0)
            put("version", item.version)
            put("importance", item.importance)
            put("access_count", item.accessCount)
            put("last_accessed_at", item.lastAccessedAt)
            put("expires_at", item.expiresAt)
            put("namespace", item.namespace)
            put("supersedes_id", item.supersedesId)
            put("confirmed", if (item.isConfirmed) 1 else 0)
        }

        db.beginTransaction()
        return try {
            val id = db.insert("memory_items", null, values)
            if (id > 0 && !item.archived) {
                val ftsValues = ContentValues().apply {
                    put("memory_id", id.toString())
                    put("key", item.key)
                    put("content", item.content)
                    put("provenance", item.provenance)
                }
                db.insert("memory_fts", null, ftsValues)
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    override fun rebuildFtsIndex() {
        val db = dbHelper.writableDatabase
        dbHelper.rebuildFts(db)
    }

    override fun setConfirmed(id: Long, confirmed: Boolean) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("confirmed", if (confirmed) 1 else 0)
        }
        db.update("memory_items", cv, "id = ?", arrayOf(id.toString()))
    }

    fun archiveByProvenance(provenance: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "memory_items",
                ContentValues().apply { put("archived", 1) },
                "provenance = ?",
                arrayOf(provenance)
            )
            db.delete("memory_fts", "provenance = ?", arrayOf(provenance))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPreferences(limit: Int = 8): List<MemoryItem> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "memory_items",
            null,
            "type = ? AND archived = 0",
            arrayOf(MemoryType.USER_PREFERENCE.name),
            null,
            null,
            "importance DESC, created_at DESC",
            limit.coerceAtLeast(1).toString()
        )
        return cursorToItems(cursor)
    }

    fun getHabits(limit: Int = 30): List<MemoryItem> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "memory_items",
            null,
            "type = ? AND archived = 0",
            arrayOf(MemoryType.LEARNED_PATTERN.name),
            null,
            null,
            "importance DESC, created_at DESC",
            limit.coerceAtLeast(1).toString()
        )
        return cursorToItems(cursor)
    }

    /**
     * Local hybrid RAG retrieval. FTS4 gives fast candidate retrieval, then
     * Jarvis re-ranks with lexical overlap, importance, recency and past usage.
     */
    fun queryRelevantMemories(
        query: String,
        types: Set<MemoryType>,
        limit: Int = 8,
        namespace: String = "all"
    ): List<MemoryItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        purgeExpired()

        val db = dbHelper.readableDatabase
        val candidates = linkedMapOf<Long, MemoryItem>()
        val effectiveTypes = if (types.isEmpty()) MemoryType.values().toSet() else types
        val typeNames = effectiveTypes.map { it.name }
        val isAllNamespace = namespace.equals("all", ignoreCase = true) || namespace.isBlank()

        val ftsTerms = tokenize(cleanQuery)
        if (ftsTerms.isNotEmpty()) {
            val match = ftsTerms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
            val nsClause = if (isAllNamespace) "" else " AND m.namespace = ?"
            val sql = """
                SELECT m.*
                FROM memory_fts f
                JOIN memory_items m ON m.id = CAST(f.memory_id AS INTEGER)
                WHERE memory_fts MATCH ?
                  AND m.archived = 0
                  $nsClause
                  AND m.type IN (${typeNames.joinToString(",") { "?" }})
                LIMIT 80
            """.trimIndent()
            val argsList = mutableListOf<String>()
            argsList.add(match)
            if (!isAllNamespace) argsList.add(namespace)
            argsList.addAll(typeNames)

            runCatching {
                db.rawQuery(sql, argsList.toTypedArray()).use { c ->
                    cursorToItems(c).forEach { candidates[it.id] = it }
                }
            }
        }

        // Fallback for queries that FTS tokenization does not match.
        if (candidates.size < limit) {
            val like = "%${cleanQuery.lowercase(Locale.US)}%"
            val nsClause = if (isAllNamespace) "" else " AND namespace = ?"
            val sql = """
                SELECT * FROM memory_items
                WHERE archived = 0
                  $nsClause
                  AND type IN (${typeNames.joinToString(",") { "?" }})
                  AND (LOWER(key) LIKE ? OR LOWER(content) LIKE ? OR LOWER(provenance) LIKE ?)
                ORDER BY importance DESC, created_at DESC
                LIMIT 80
            """.trimIndent()
            val argsList = mutableListOf<String>()
            if (!isAllNamespace) argsList.add(namespace)
            argsList.addAll(typeNames)
            argsList.add(like); argsList.add(like); argsList.add(like)

            runCatching {
                db.rawQuery(sql, argsList.toTypedArray()).use { c ->
                    cursorToItems(c).forEach { candidates.putIfAbsent(it.id, it) }
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val queryTokens = tokenize(cleanQuery).toSet()

        return candidates.values
            .map { item ->
                val textTokens = tokenize("${item.key} ${item.content} ${item.provenance}")
                val overlap = if (queryTokens.isEmpty()) 0.0 else {
                    queryTokens.count { q -> textTokens.contains(q) }.toDouble() / queryTokens.size
                }
                val ageHours = ((now - item.createdAt).coerceAtLeast(0L) / 3_600_000.0)
                val recency = exp(-ageHours / (24.0 * 30.0))
                val usage = ln(1.0 + item.accessCount.toDouble()).coerceIn(0.0, 2.0) / 2.0
                val combined = (
                    overlap * 0.52 +
                    item.importance * 0.23 +
                    recency * 0.15 +
                    usage * 0.10
                ).coerceIn(0.0, 1.0)
                item.copy(score = combined)
            }
            .filter { it.score >= 0.10 }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
            .also { results -> results.forEach { touch(it.id) } }
    }

    fun saveConversation(sessionId: String, role: String, content: String) {
        if (content.isBlank()) return
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("session_id", sessionId.ifBlank { "default" })
            put("role", role)
            put("content", content.trim())
            put("created_at", System.currentTimeMillis())
        }
        db.insert("conversation_messages", null, values)
    }

    fun getRecentConversation(sessionId: String, limit: Int = 8): List<ConversationRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "conversation_messages",
            arrayOf("id", "session_id", "role", "content", "created_at"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "created_at DESC",
            limit.coerceAtLeast(1).toString()
        )
        val list = mutableListOf<ConversationRecord>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ConversationRecord(
                        id = it.getLong(0),
                        sessionId = it.getString(1),
                        role = it.getString(2),
                        content = it.getString(3),
                        createdAt = it.getLong(4)
                    )
                )
            }
        }
        return list.asReversed()
    }

    fun stats(): MemoryStats {
        val db = dbHelper.readableDatabase
        fun count(table: String, where: String? = null, args: Array<String>? = null): Int =
            db.rawQuery("SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: ""), args).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        val memories = count("memory_items", "archived = 0")
        val archived = count("memory_items", "archived = 1")
        val conversations = count("conversation_messages")
        val documents = count("memory_items", "archived = 0 AND type = ?", arrayOf(MemoryType.DOCUMENT_SNIPPET.name))
        val preferences = count("memory_items", "archived = 0 AND type = ?", arrayOf(MemoryType.USER_PREFERENCE.name))
        return MemoryStats(memories, archived, conversations, documents, preferences)
    }

    fun recentMemories(limit: Int = 20): List<MemoryItem> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "memory_items", null, "archived = 0", null, null, null,
            "created_at DESC", limit.coerceIn(1, 100).toString()
        )
        return cursorToItems(cursor)
    }

    fun clearArchived() {
        dbHelper.writableDatabase.delete("memory_items", "archived = 1", null)
    }

    /** Drop memories whose ttl elapsed. Runs before reads so expiry is side-effect free. */
    fun purgeExpired(): Int {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            db.delete("memory_fts", "memory_id IN (SELECT id FROM memory_items WHERE expires_at IS NOT NULL AND expires_at <= ?)", arrayOf(now.toString()))
            val removed = db.delete("memory_items", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(now.toString()))
            db.setTransactionSuccessful()
            removed
        } finally {
            db.endTransaction()
        }
    }

    fun pruneConversations(keepLatest: Int = 500) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            """
            DELETE FROM conversation_messages
            WHERE id NOT IN (
                SELECT id FROM conversation_messages
                ORDER BY created_at DESC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(keepLatest.coerceAtLeast(50))
        )
    }

    private fun touch(id: Long) {
        val db = dbHelper.writableDatabase
        db.execSQL(
            "UPDATE memory_items SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

    private fun cursorToItems(cursor: android.database.Cursor): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    MemoryItem(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        type = runCatching { MemoryType.valueOf(it.getString(it.getColumnIndexOrThrow("type"))) }.getOrDefault(MemoryType.SHORT_TERM),
                        key = it.getString(it.getColumnIndexOrThrow("key")),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        provenance = it.getString(it.getColumnIndexOrThrow("provenance")),
                        score = it.getDouble(it.getColumnIndexOrThrow("score")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                        archived = it.getInt(it.getColumnIndexOrThrow("archived")) == 1,
                        version = it.getInt(it.getColumnIndexOrThrow("version")),
                        importance = getOptionalDouble(it, "importance", 0.5),
                        accessCount = getOptionalLong(it, "access_count", 0),
                        lastAccessedAt = getOptionalNullableLong(it, "last_accessed_at"),
                        expiresAt = getOptionalNullableLong(it, "expires_at"),
                        namespace = getOptionalString(it, "namespace", "default"),
                        supersedesId = getOptionalNullableString(it, "supersedes_id"),
                        isConfirmed = getOptionalInt(it, "confirmed", 0) == 1
                    )
                )
            }
        }
        return list
    }

    private fun getOptionalDouble(c: android.database.Cursor, name: String, default: Double): Double =
        c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getDouble) ?: default

    private fun getOptionalLong(c: android.database.Cursor, name: String, default: Long): Long =
        c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getLong) ?: default

    private fun getOptionalInt(c: android.database.Cursor, name: String, default: Int): Int =
        c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getInt) ?: default

    private fun getOptionalNullableLong(c: android.database.Cursor, name: String): Long? {
        val index = c.getColumnIndex(name)
        if (index < 0 || c.isNull(index)) return null
        return c.getLong(index)
    }

    private fun getOptionalNullableString(c: android.database.Cursor, name: String): String? {
        val index = c.getColumnIndex(name)
        if (index < 0 || c.isNull(index)) return null
        return c.getString(index)
    }

    private fun getOptionalString(c: android.database.Cursor, name: String, default: String): String =
        c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getString) ?: default
}
