package com.jarvis.data.repository

import android.content.ContentValues
import android.database.Cursor
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.model.TaskAuditLog
import com.jarvis.data.model.TaskRecord

class TaskRepository(private val dbHelper: JarvisDatabase) {

    fun createTask(goal: String, priority: Int = 0): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("goal", goal)
            put("status", "CREATED")
            put("priority", priority)
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        return db.insert("persistent_tasks", null, values)
    }

    fun updateTaskStatus(taskId: Long, status: String, summary: String? = null, failureReason: String? = null) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
            if (summary != null) put("result_summary", summary)
            if (failureReason != null) put("failure_reason", failureReason)
            if (status == "COMPLETED" || status == "FAILED" || status == "CANCELLED") {
                put("completed_at", System.currentTimeMillis())
            }
        }
        db.update("persistent_tasks", values, "id = ?", arrayOf(taskId.toString()))
    }

    fun logAudit(taskId: Long?, actionType: String, riskLevel: String, reason: String?, status: String, details: String?) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("action_type", actionType)
            put("risk_level", riskLevel)
            put("reason", reason)
            put("status", status)
            put("details", details)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("task_audit_logs", null, values)
    }

    fun getActiveTasks(): List<TaskRecord> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<TaskRecord>()
        val activeStatuses = arrayOf("CREATED", "PLANNED", "EXECUTING", "OBSERVING", "VERIFYING", "WAITING_FOR_USER")
        val placeholders = activeStatuses.joinToString(",") { "?" }

        db.query("persistent_tasks", null, "status IN ($placeholders)", activeStatuses, null, null, "priority DESC, created_at ASC").use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapCursorToTask(cursor))
            }
        }
        return results
    }

    fun getAuditLogsForTask(taskId: Long): List<TaskAuditLog> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<TaskAuditLog>()
        db.query("task_audit_logs", null, "task_id = ?", arrayOf(taskId.toString()), null, null, "timestamp ASC").use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    TaskAuditLog(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        taskId = cursor.getLong(cursor.getColumnIndexOrThrow("task_id")),
                        actionType = cursor.getString(cursor.getColumnIndexOrThrow("action_type")),
                        riskLevel = cursor.getString(cursor.getColumnIndexOrThrow("risk_level")),
                        reason = cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        details = cursor.getString(cursor.getColumnIndexOrThrow("details")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return results
    }

    private fun mapCursorToTask(cursor: Cursor): TaskRecord {
        return TaskRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            goal = cursor.getString(cursor.getColumnIndexOrThrow("goal")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            resultSummary = cursor.getString(cursor.getColumnIndexOrThrow("result_summary")),
            failureReason = cursor.getString(cursor.getColumnIndexOrThrow("failure_reason")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            completedAt = cursor.getLong(cursor.getColumnIndexOrThrow("completed_at"))
        )
    }
}
