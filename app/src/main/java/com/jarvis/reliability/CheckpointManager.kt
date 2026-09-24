package com.jarvis.reliability

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Checkpoint(
    val checkpointId: Long = 0,
    val taskId: Long,
    val stepIndex: Int,
    val stateJson: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ReliabilityDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE checkpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                state_json TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE executed_action_hashes (
                hash TEXT PRIMARY KEY,
                action_type TEXT NOT NULL,
                executed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive migration: ensure tables exist without dropping user checkpoints or hashes
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS checkpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                state_json TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS executed_action_hashes (
                hash TEXT PRIMARY KEY,
                action_type TEXT NOT NULL,
                executed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    companion object {
        const val DATABASE_NAME = "jarvis_reliability.db"
        const val DATABASE_VERSION = 1
    }
}

class CheckpointManager(context: Context) {
    private val dbHelper = ReliabilityDatabaseHelper(com.jarvis.storage.JarvisStorageHub.createDatabaseContext(context.applicationContext))

    fun saveCheckpoint(taskId: Long, stepIndex: Int, stateJson: String): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("step_index", stepIndex)
            put("state_json", stateJson)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("checkpoints", null, values)
    }

    fun getLatestCheckpoint(taskId: Long): Checkpoint? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "checkpoints",
            null,
            "task_id = ?",
            arrayOf(taskId.toString()),
            null,
            null,
            "step_index DESC, timestamp DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return Checkpoint(
                    checkpointId = it.getLong(it.getColumnIndexOrThrow("id")),
                    taskId = it.getLong(it.getColumnIndexOrThrow("task_id")),
                    stepIndex = it.getInt(it.getColumnIndexOrThrow("step_index")),
                    stateJson = it.getString(it.getColumnIndexOrThrow("state_json")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                )
            }
        }
        return null
    }

    fun isActionIdempotentDuplicate(actionHash: String, windowMs: Long = 15_000L): Boolean {
        val db = dbHelper.readableDatabase
        val cutoff = System.currentTimeMillis() - windowMs
        val cursor = db.query(
            "executed_action_hashes",
            null,
            "hash = ? AND executed_at > ?",
            arrayOf(actionHash, cutoff.toString()),
            null,
            null,
            null
        )
        return cursor.use { it.count > 0 }
    }

    /**
     * Removes a claimed action hash so a genuinely-failed action can be retried.
     * Claims are recorded BEFORE execution (so a lost-success can never double-fire)
     * and cleared after a hard failure (so a real failure is not masked).
     */
    fun clearActionExecution(actionHash: String) {
        val db = dbHelper.writableDatabase
        db.delete("executed_action_hashes", "hash = ?", arrayOf(actionHash))
    }

    fun recordActionExecution(actionHash: String, actionType: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("hash", actionHash)
            put("action_type", actionType)
            put("executed_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "executed_action_hashes",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
