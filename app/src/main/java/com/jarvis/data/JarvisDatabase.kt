package com.jarvis.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single authoritative SQLite database helper for the entire Jarvis assistant runtime.
 * Consolidates fragmented databases (memories, RAG documents, embeddings, persistent tasks,
 * observations, audit logs) into a single transactional schema.
 */
class JarvisDatabase(context: Context) : SQLiteOpenHelper(
    com.jarvis.storage.JarvisStorageHub.createDatabaseContext(context.applicationContext),
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "jarvis_unified.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: JarvisDatabase? = null

        fun getInstance(context: Context): JarvisDatabase =
            instance ?: synchronized(this) {
                instance ?: JarvisDatabase(context).also { instance = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            // 1. Unified Memories Schema (Temporal & Provenance Aware)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    namespace TEXT NOT NULL,
                    type TEXT NOT NULL,
                    subject_id TEXT,
                    key TEXT NOT NULL,
                    content TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    importance REAL NOT NULL DEFAULT 0.5,
                    source_type TEXT NOT NULL,
                    source_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER,
                    expires_at INTEGER,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    version INTEGER NOT NULL DEFAULT 1,
                    superseded_by INTEGER,
                    FOREIGN KEY(superseded_by) REFERENCES memories(id)
                );
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_lookup ON memories(namespace, type, subject_id, status);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_key ON memories(namespace, key);")

            // 2. RAG Documents & Chunks Schema
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    source_uri TEXT,
                    mime_type TEXT,
                    checksum TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS document_chunks (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    token_count INTEGER NOT NULL DEFAULT 0,
                    checksum TEXT NOT NULL,
                    metadata_json TEXT,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                );
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_doc ON document_chunks(document_id);")

            // 3. Dense Vector Embeddings Schema
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS embeddings (
                    chunk_id TEXT PRIMARY KEY,
                    model TEXT NOT NULL,
                    dimensions INTEGER NOT NULL,
                    vector BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(chunk_id) REFERENCES document_chunks(id) ON DELETE CASCADE
                );
            """.trimIndent())

            // 4. Persistent Tasks & Step Audit Schema
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS persistent_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    goal TEXT NOT NULL,
                    status TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    result_summary TEXT,
                    failure_reason TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER
                );
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS task_audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER,
                    action_type TEXT NOT NULL,
                    risk_level TEXT NOT NULL,
                    reason TEXT,
                    status TEXT NOT NULL,
                    details TEXT,
                    timestamp INTEGER NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES persistent_tasks(id) ON DELETE SET NULL
                );
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_audit ON task_audit_logs(task_id);")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations will be handled iteratively here
    }
}
