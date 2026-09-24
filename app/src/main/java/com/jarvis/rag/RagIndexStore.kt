package com.jarvis.rag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import kotlin.math.ln

data class RagStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val totalWords: Int,
    val avgChunkLength: Double
)

data class TermPosting(
    val chunkId: String,
    val docId: String,
    val freq: Int
)

class RagDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rag_documents (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                source_path TEXT NOT NULL,
                checksum TEXT NOT NULL,
                total_chunks INTEGER NOT NULL,
                total_words INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rag_chunks (
                id TEXT PRIMARY KEY,
                doc_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                total_chunks INTEGER NOT NULL,
                title TEXT NOT NULL,
                source_path TEXT NOT NULL,
                content TEXT NOT NULL,
                word_count INTEGER NOT NULL,
                checksum TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc ON rag_chunks(doc_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rag_term_freq (
                term TEXT NOT NULL,
                chunk_id TEXT NOT NULL,
                doc_id TEXT NOT NULL,
                freq INTEGER NOT NULL,
                PRIMARY KEY (term, chunk_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rag_tf_term ON rag_term_freq(term)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Upgrade preserves documents if table exists
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "jarvis_rag_vault.db"
        const val DATABASE_VERSION = 1
    }
}

interface IRagIndexStore {
    fun indexDocument(metadata: DocumentMetadata, chunks: List<DocumentChunk>)
    fun deleteDocument(docId: String)
    fun getChunk(chunkId: String): DocumentChunk?
    fun getAllChunks(): List<DocumentChunk>
    fun getChunksForDocument(docId: String): List<DocumentChunk>
    fun getAllDocuments(): List<DocumentMetadata>
    fun getTermPostings(term: String): List<TermPosting>
    fun getStats(): RagStats
}

class RagIndexStore(context: Context) : IRagIndexStore {
    private val dbHelper = RagDatabaseHelper(com.jarvis.storage.JarvisStorageHub.createDatabaseContext(context.applicationContext))

    companion object {
        val STOP_WORDS = setOf(
            "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
            "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
            "below", "between", "both", "but", "by", "can't", "cannot", "could", "couldn't",
            "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during",
            "each", "few", "for", "from", "further", "had", "hadn't", "has", "hasn't",
            "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here",
            "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i",
            "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's",
            "its", "itself", "let's", "me", "more", "most", "mustn't", "my", "myself",
            "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought",
            "our", "ours", "ourselves", "out", "over", "own", "same", "shan't", "she",
            "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such", "than",
            "that", "that's", "the", "their", "theirs", "them", "themselves", "then", "there",
            "there's", "these", "they", "they'd", "they'll", "they're", "they've", "this",
            "those", "through", "to", "too", "under", "until", "up", "very", "was", "wasn't",
            "we", "we'd", "we'll", "we're", "we've", "were", "weren't", "what", "what's",
            "when", "when's", "where", "where's", "which", "while", "who", "who's", "whom",
            "why", "why's", "with", "won't", "would", "wouldn't", "you", "you'd", "you'll",
            "you're", "you've", "your", "yours", "yourself", "yourselves"
        )

        fun tokenize(text: String): List<String> {
            return text.lowercase(Locale.US)
                .split(Regex("[^\\p{L}\\p{N}_]+"))
                .map { it.trim() }
                .filter { it.length >= 2 && !STOP_WORDS.contains(it) }
        }
    }

    @Synchronized
    override fun indexDocument(metadata: DocumentMetadata, chunks: List<DocumentChunk>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Delete old data for this docId if present
            deleteDocumentInternal(db, metadata.docId)

            // Insert document metadata
            val docValues = ContentValues().apply {
                put("id", metadata.docId)
                put("title", metadata.title)
                put("source_path", metadata.sourcePath)
                put("checksum", metadata.checksum)
                put("total_chunks", metadata.totalChunks)
                put("total_words", metadata.totalWords)
                put("created_at", metadata.createdAt)
                put("updated_at", metadata.updatedAt)
            }
            db.insertWithOnConflict("rag_documents", null, docValues, SQLiteDatabase.CONFLICT_REPLACE)

            // Insert chunks & inverted index terms
            for (chunk in chunks) {
                val chunkValues = ContentValues().apply {
                    put("id", chunk.chunkId)
                    put("doc_id", chunk.docId)
                    put("chunk_index", chunk.chunkIndex)
                    put("total_chunks", chunk.totalChunks)
                    put("title", chunk.title)
                    put("source_path", chunk.sourcePath)
                    put("content", chunk.content)
                    put("word_count", chunk.wordCount)
                    put("checksum", chunk.checksum)
                    put("created_at", chunk.createdAt)
                }
                db.insertWithOnConflict("rag_chunks", null, chunkValues, SQLiteDatabase.CONFLICT_REPLACE)

                // Compute term frequencies for inverted index
                val tokens = tokenize("${chunk.title} ${chunk.content}")
                val termFreqs = mutableMapOf<String, Int>()
                for (token in tokens) {
                    termFreqs[token] = (termFreqs[token] ?: 0) + 1
                }

                for ((term, freq) in termFreqs) {
                    val tfValues = ContentValues().apply {
                        put("term", term)
                        put("chunk_id", chunk.chunkId)
                        put("doc_id", chunk.docId)
                        put("freq", freq)
                    }
                    db.insertWithOnConflict("rag_term_freq", null, tfValues, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun deleteDocument(docId: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            deleteDocumentInternal(db, docId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun deleteDocumentInternal(db: SQLiteDatabase, docId: String) {
        db.delete("rag_documents", "id = ?", arrayOf(docId))
        db.delete("rag_chunks", "doc_id = ?", arrayOf(docId))
        db.delete("rag_term_freq", "doc_id = ?", arrayOf(docId))
    }

    override fun getChunk(chunkId: String): DocumentChunk? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "rag_chunks",
            null,
            "id = ?",
            arrayOf(chunkId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToChunk(it) else null
        }
    }

    override fun getAllChunks(): List<DocumentChunk> {
        val db = dbHelper.readableDatabase
        val cursor = db.query("rag_chunks", null, null, null, null, null, "doc_id, chunk_index ASC")
        val list = mutableListOf<DocumentChunk>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToChunk(it))
            }
        }
        return list
    }

    override fun getChunksForDocument(docId: String): List<DocumentChunk> {
        val db = dbHelper.readableDatabase
        val cursor = db.query("rag_chunks", null, "doc_id = ?", arrayOf(docId), null, null, "chunk_index ASC")
        val list = mutableListOf<DocumentChunk>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToChunk(it))
            }
        }
        return list
    }

    override fun getAllDocuments(): List<DocumentMetadata> {
        val db = dbHelper.readableDatabase
        val cursor = db.query("rag_documents", null, null, null, null, null, "created_at DESC")
        val list = mutableListOf<DocumentMetadata>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    DocumentMetadata(
                        docId = it.getString(it.getColumnIndexOrThrow("id")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        sourcePath = it.getString(it.getColumnIndexOrThrow("source_path")),
                        checksum = it.getString(it.getColumnIndexOrThrow("checksum")),
                        totalChunks = it.getInt(it.getColumnIndexOrThrow("total_chunks")),
                        totalWords = it.getInt(it.getColumnIndexOrThrow("total_words")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return list
    }

    override fun getTermPostings(term: String): List<TermPosting> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "rag_term_freq",
            arrayOf("chunk_id", "doc_id", "freq"),
            "term = ?",
            arrayOf(term.lowercase(Locale.US)),
            null, null, null
        )
        val list = mutableListOf<TermPosting>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TermPosting(
                        chunkId = it.getString(0),
                        docId = it.getString(1),
                        freq = it.getInt(2)
                    )
                )
            }
        }
        return list
    }

    override fun getStats(): RagStats {
        val db = dbHelper.readableDatabase
        var totalDocs = 0
        var totalChunks = 0
        var totalWords = 0

        db.rawQuery("SELECT COUNT(*) FROM rag_documents", null).use {
            if (it.moveToFirst()) totalDocs = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(word_count), 0) FROM rag_chunks", null).use {
            if (it.moveToFirst()) {
                totalChunks = it.getInt(0)
                totalWords = it.getInt(1)
            }
        }

        val avgChunkLen = if (totalChunks > 0) totalWords.toDouble() / totalChunks else 0.0
        return RagStats(
            totalDocuments = totalDocs,
            totalChunks = totalChunks,
            totalWords = totalWords,
            avgChunkLength = avgChunkLen
        )
    }

    private fun cursorToChunk(c: android.database.Cursor): DocumentChunk {
        return DocumentChunk(
            chunkId = c.getString(c.getColumnIndexOrThrow("id")),
            docId = c.getString(c.getColumnIndexOrThrow("doc_id")),
            chunkIndex = c.getInt(c.getColumnIndexOrThrow("chunk_index")),
            totalChunks = c.getInt(c.getColumnIndexOrThrow("total_chunks")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            sourcePath = c.getString(c.getColumnIndexOrThrow("source_path")),
            content = c.getString(c.getColumnIndexOrThrow("content")),
            wordCount = c.getInt(c.getColumnIndexOrThrow("word_count")),
            checksum = c.getString(c.getColumnIndexOrThrow("checksum")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }
}

