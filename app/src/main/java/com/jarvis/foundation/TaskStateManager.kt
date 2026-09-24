package com.jarvis.foundation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TaskRecord(
    val id: Long = 0,
    val goal: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val result: String? = null,
    val iteration: Int = 0,
    val currentObjective: String? = null
)

data class StepRecord(
    val id: Long = 0,
    val taskId: Long,
    val stepIndex: Int,
    val actionType: String,
    val paramsJson: String,
    val status: String,
    val resultMessage: String? = null,
    val timestamp: Long
)

data class AuditLogRecord(
    val id: Long = 0,
    val taskId: Long?,
    val actionType: String,
    val riskLevel: String,
    val reason: String,
    val status: String,
    val details: String,
    val timestamp: Long
)

data class AgentExperienceRecord(
    val id: Long = 0,
    val taskType: String,
    val goalPattern: String,
    val strategy: String,
    val action: String?,
    val result: String,
    val success: Boolean,
    val feedback: String?,
    val createdAt: Long
)

class JarvisDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                result TEXT,
                iteration INTEGER DEFAULT 0,
                current_objective TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                action_type TEXT NOT NULL,
                params_json TEXT NOT NULL,
                status TEXT NOT NULL,
                result_message TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER,
                action_type TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                reason TEXT NOT NULL,
                status TEXT NOT NULL,
                details TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_id INTEGER,
                source TEXT NOT NULL,
                success INTEGER NOT NULL,
                message TEXT NOT NULL,
                data_json TEXT,
                confidence REAL DEFAULT 1.0,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_decisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                iteration INTEGER NOT NULL,
                decision_type TEXT NOT NULL,
                reason_code TEXT,
                action_type TEXT,
                params_json TEXT,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_experiences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_type TEXT NOT NULL,
                goal_pattern TEXT NOT NULL,
                strategy TEXT NOT NULL,
                action TEXT,
                result TEXT NOT NULL,
                success INTEGER NOT NULL,
                feedback TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTables(db)
            addColumnIfMissing(db, "tasks", "iteration", "INTEGER DEFAULT 0")
            addColumnIfMissing(db, "tasks", "current_objective", "TEXT")
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        var exists = false
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (it.getString(nameIndex).equals(column, ignoreCase = true)) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    companion object {
        const val DATABASE_NAME = "jarvis_foundation.db"
        const val DATABASE_VERSION = 2
    }
}

class TaskStateManager(context: Context) {
    private val dbHelper = JarvisDatabaseHelper(com.jarvis.storage.JarvisStorageHub.createDatabaseContext(context.applicationContext))

    fun createTask(goal: String): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("goal", goal)
            put("status", "PENDING")
            put("created_at", System.currentTimeMillis())
            put("iteration", 0)
        }
        return db.insert("tasks", null, values)
    }

    fun updateTaskStatus(taskId: Long, status: String, result: String? = null) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            if (status == "COMPLETED" || status == "FAILED") {
                put("completed_at", System.currentTimeMillis())
            }
            if (result != null) {
                put("result", result)
            }
        }
        db.update("tasks", values, "id = ?", arrayOf(taskId.toString()))
    }

    fun updateTaskIteration(taskId: Long, iteration: Int, currentObjective: String? = null) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("iteration", iteration)
            if (currentObjective != null) {
                put("current_objective", currentObjective)
            }
        }
        db.update("tasks", values, "id = ?", arrayOf(taskId.toString()))
    }

    fun recordStep(
        taskId: Long,
        stepIndex: Int,
        actionType: String,
        paramsJson: String,
        status: String,
        resultMessage: String?
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("step_index", stepIndex)
            put("action_type", actionType)
            put("params_json", paramsJson)
            put("status", status)
            put("result_message", resultMessage)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("steps", null, values)
    }

    fun recordObservation(
        taskId: Long,
        stepId: Long?,
        source: String,
        success: Boolean,
        message: String,
        dataJson: String? = null,
        confidence: Float = 1.0f
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_id", taskId)
            if (stepId != null) put("step_id", stepId)
            put("source", source)
            put("success", if (success) 1 else 0)
            put("message", message)
            if (dataJson != null) put("data_json", dataJson)
            put("confidence", confidence)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("agent_observations", null, values)
    }

    fun recordDecision(
        taskId: Long,
        iteration: Int,
        decisionType: String,
        reasonCode: String? = null,
        actionType: String? = null,
        paramsJson: String? = null
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("iteration", iteration)
            put("decision_type", decisionType)
            if (reasonCode != null) put("reason_code", reasonCode)
            if (actionType != null) put("action_type", actionType)
            if (paramsJson != null) put("params_json", paramsJson)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("agent_decisions", null, values)
    }

    fun saveExperience(
        taskType: String,
        goalPattern: String,
        strategy: String,
        action: String?,
        result: String,
        success: Boolean,
        feedback: String? = null
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_type", taskType)
            put("goal_pattern", goalPattern)
            put("strategy", strategy)
            if (action != null) put("action", action)
            put("result", result)
            put("success", if (success) 1 else 0)
            if (feedback != null) put("feedback", feedback)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("agent_experiences", null, values)
    }

    fun findExperiences(goalPattern: String, limit: Int = 3): List<AgentExperienceRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "agent_experiences",
            null,
            "goal_pattern LIKE ? OR task_type LIKE ?",
            arrayOf("%$goalPattern%", "%$goalPattern%"),
            null,
            null,
            "created_at DESC",
            limit.toString()
        )
        return readExperienceRecords(cursor)
    }

    fun findRecentExperiences(limit: Int = 50): List<AgentExperienceRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "agent_experiences",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString()
        )
        return readExperienceRecords(cursor)
    }

    private fun readExperienceRecords(cursor: android.database.Cursor): List<AgentExperienceRecord> {
        val list = mutableListOf<AgentExperienceRecord>()
        cursor.use {
            val idIdx = it.getColumnIndex("id")
            val typeIdx = it.getColumnIndex("task_type")
            val goalIdx = it.getColumnIndex("goal_pattern")
            val stratIdx = it.getColumnIndex("strategy")
            val actIdx = it.getColumnIndex("action")
            val resIdx = it.getColumnIndex("result")
            val succIdx = it.getColumnIndex("success")
            val feedIdx = it.getColumnIndex("feedback")
            val timeIdx = it.getColumnIndex("created_at")

            while (it.moveToNext()) {
                list.add(
                    AgentExperienceRecord(
                        id = it.getLong(idIdx),
                        taskType = it.getString(typeIdx),
                        goalPattern = it.getString(goalIdx),
                        strategy = it.getString(stratIdx),
                        action = if (actIdx >= 0) it.getString(actIdx) else null,
                        result = it.getString(resIdx),
                        success = it.getInt(succIdx) == 1,
                        feedback = if (feedIdx >= 0) it.getString(feedIdx) else null,
                        createdAt = it.getLong(timeIdx)
                    )
                )
            }
        }
        return list
    }

    fun getTask(taskId: Long): TaskRecord? {
        val db = dbHelper.readableDatabase
        val cursor = db.query("tasks", null, "id = ?", arrayOf(taskId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val idIdx = it.getColumnIndex("id")
                val goalIdx = it.getColumnIndex("goal")
                val statusIdx = it.getColumnIndex("status")
                val createdIdx = it.getColumnIndex("created_at")
                val compIdx = it.getColumnIndex("completed_at")
                val resIdx = it.getColumnIndex("result")
                val iterIdx = it.getColumnIndex("iteration")
                val objIdx = it.getColumnIndex("current_objective")

                return TaskRecord(
                    id = it.getLong(idIdx),
                    goal = it.getString(goalIdx),
                    status = it.getString(statusIdx),
                    createdAt = it.getLong(createdIdx),
                    completedAt = if (compIdx >= 0 && !it.isNull(compIdx)) it.getLong(compIdx) else null,
                    result = if (resIdx >= 0 && !it.isNull(resIdx)) it.getString(resIdx) else null,
                    iteration = if (iterIdx >= 0) it.getInt(iterIdx) else 0,
                    currentObjective = if (objIdx >= 0 && !it.isNull(objIdx)) it.getString(objIdx) else null
                )
            }
        }
        return null
    }

    fun getRecentTasks(limit: Int = 10): List<TaskRecord> {
        val db = dbHelper.readableDatabase
        val cursor = db.query("tasks", null, null, null, null, null, "created_at DESC", limit.toString())
        val list = mutableListOf<TaskRecord>()
        cursor.use {
            val idIdx = it.getColumnIndex("id")
            val goalIdx = it.getColumnIndex("goal")
            val statusIdx = it.getColumnIndex("status")
            val createdIdx = it.getColumnIndex("created_at")
            val compIdx = it.getColumnIndex("completed_at")
            val resIdx = it.getColumnIndex("result")
            val iterIdx = it.getColumnIndex("iteration")
            val objIdx = it.getColumnIndex("current_objective")

            while (it.moveToNext()) {
                list.add(
                    TaskRecord(
                        id = it.getLong(idIdx),
                        goal = it.getString(goalIdx),
                        status = it.getString(statusIdx),
                        createdAt = it.getLong(createdIdx),
                        completedAt = if (compIdx >= 0 && !it.isNull(compIdx)) it.getLong(compIdx) else null,
                        result = if (resIdx >= 0 && !it.isNull(resIdx)) it.getString(resIdx) else null,
                        iteration = if (iterIdx >= 0) it.getInt(iterIdx) else 0,
                        currentObjective = if (objIdx >= 0 && !it.isNull(objIdx)) it.getString(objIdx) else null
                    )
                )
            }
        }
        return list
    }

    fun logAudit(
        taskId: Long?,
        actionType: String,
        riskLevel: String,
        reason: String,
        status: String,
        details: String
    ): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (taskId != null) put("task_id", taskId)
            put("action_type", actionType)
            put("risk_level", riskLevel)
            put("reason", reason)
            put("status", status)
            put("details", details)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("audit_logs", null, values)
    }
}
