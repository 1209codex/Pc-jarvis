package com.jarvis.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.jarvis.ai.EmbeddingVectorizer
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VectorRecord(
    val id: String,
    val topic: String,
    val text: String,
    val vector: FloatArray,
    val dimensions: Int,
    val metadata: Map<String, String>,
    val createdAt: Long
)

data class VectorMatch(
    val id: String,
    val topic: String,
    val text: String,
    val score: Double,
    val metadata: Map<String, String>
)

data class VectorDbStats(
    val totalVectors: Int,
    val dimensions: Int,
    val storageSizeBytes: Long,
    val lastUpdated: String,
    val dbPath: String
)

class VectorDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vector_entries (
                id TEXT PRIMARY KEY,
                topic TEXT NOT NULL,
                text_content TEXT NOT NULL,
                vector_blob BLOB NOT NULL,
                dimensions INTEGER NOT NULL,
                metadata_json TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_topic ON vector_entries(topic)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vectors_created ON vector_entries(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        const val DATABASE_NAME = "vectors.db"
        const val DATABASE_VERSION = 1
    }
}

class VectorDbManager(private val context: Context) {
    private val TAG = "VectorDbManager"
    private val vectorizer = EmbeddingVectorizer(dims = 256)
    private val dbContext = DatabaseContext(context, JarvisStorageHub.getVectorDbDirectory(context))
    private val dbHelper = VectorDatabaseHelper(dbContext)

    init {
        JarvisStorageHub.initStorage(context)
        saveManifest()
    }

    fun storeVector(
        id: String,
        text: String,
        topic: String = "general",
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            val vec = vectorizer.embed(text)
            val blob = floatArrayToByteArray(vec)
            val metaJson = JSONObject(metadata).toString()
            val now = System.currentTimeMillis()

            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("id", id)
                put("topic", topic)
                put("text_content", text)
                put("vector_blob", blob)
                put("dimensions", vec.size)
                put("metadata_json", metaJson)
                put("created_at", now)
            }

            db.insertWithOnConflict("vector_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            saveManifest()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store vector '$id': ${e.message}", e)
            false
        }
    }

    fun searchSimilar(query: String, topK: Int = 5, minScore: Double = 0.25): List<VectorMatch> {
        val queryVec = vectorizer.embed(query)
        val allRecords = getAllVectors()

        val scored = allRecords.map { record ->
            val sim = vectorizer.cosine(queryVec, record.vector)
            VectorMatch(
                id = record.id,
                topic = record.topic,
                text = record.text,
                score = sim,
                metadata = record.metadata
            )
        }

        return scored
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    fun deleteVector(id: String): Boolean {
        return try {
            val db = dbHelper.writableDatabase
            val deleted = db.delete("vector_entries", "id = ?", arrayOf(id)) > 0
            saveManifest()
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete vector '$id': ${e.message}", e)
            false
        }
    }

    fun getStats(): VectorDbStats {
        val dbFile = File(JarvisStorageHub.getVectorDbDirectory(context), VectorDatabaseHelper.DATABASE_NAME)
        val count = getCount()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

        return VectorDbStats(
            totalVectors = count,
            dimensions = 256,
            storageSizeBytes = if (dbFile.exists()) dbFile.length() else 0L,
            lastUpdated = nowStr,
            dbPath = dbFile.absolutePath
        )
    }

    private fun getCount(): Int {
        return try {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM vector_entries", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun getAllVectors(): List<VectorRecord> {
        val list = mutableListOf<VectorRecord>()
        return try {
            val db = dbHelper.readableDatabase
            db.query(
                "vector_entries",
                arrayOf("id", "topic", "text_content", "vector_blob", "dimensions", "metadata_json", "created_at"),
                null, null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val topic = cursor.getString(1)
                    val text = cursor.getString(2)
                    val blob = cursor.getBlob(3)
                    val dims = cursor.getInt(4)
                    val metaStr = cursor.getString(5)
                    val createdAt = cursor.getLong(6)

                    val vec = byteArrayToFloatArray(blob, dims)
                    val meta = parseMetadata(metaStr)

                    list.add(VectorRecord(id, topic, text, vec, dims, meta, createdAt))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read vector records: ${e.message}", e)
            emptyList()
        }
    }

    private fun saveManifest() {
        try {
            val dir = JarvisStorageHub.getVectorDbDirectory(context)
            val manifestFile = File(dir, "vector_manifest.json")
            val stats = getStats()

            val json = JSONObject().apply {
                put("engine", "JARVIS Dense Vector Database")
                put("dimensions", stats.dimensions)
                put("total_vectors", stats.totalVectors)
                put("size_bytes", stats.storageSizeBytes)
                put("db_path", stats.dbPath)
                put("last_updated", stats.lastUpdated)
                put("location", "/storage/emulated/0/jarvis-db/vector-db/")
            }

            manifestFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save vector manifest: ${e.message}")
        }
    }

    private fun parseMetadata(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                map[key] = json.optString(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray, dims: Int): FloatArray {
        val floats = FloatArray(dims)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until minOf(dims, bytes.size / 4)) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }
}
