package com.jarvis.skills.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.jarvis.foundation.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository interface and SQLite implementation for persistent skills and webapps.
 */
class PersistentSkillStore(context: Context) {
    private val TAG = "PersistentSkillStore"
    private val dbHelper = SkillDatabaseHelper(context.applicationContext)

    fun saveSkill(skill: PersistedSkill): Boolean {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        return try {
            val cv = ContentValues().apply {
                put("id", skill.id)
                put("name", skill.name)
                put("description", skill.description)
                put("triggers_json", JSONArray(skill.triggers).toString())
                put("type", skill.type.name)
                put("version", skill.version)
                put("confidence", skill.confidence)
                put("status", skill.status)
                put("risk_level", skill.riskLevel.name)
                put("config_json", skill.configJson)
                put("created_at", skill.createdAt)
                put("updated_at", System.currentTimeMillis())
            }

            db.insertWithOnConflict(
                SkillDatabaseHelper.TABLE_SKILLS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )

            // Replace steps
            db.delete(SkillDatabaseHelper.TABLE_SKILL_STEPS, "skill_id = ?", arrayOf(skill.id))
            for (step in skill.steps) {
                val stepCv = ContentValues().apply {
                    put("skill_id", skill.id)
                    put("step_index", step.stepIndex)
                    put("action_type", step.actionType)
                    put("params_json", JSONObject(step.params).toString())
                }
                db.insert(SkillDatabaseHelper.TABLE_SKILL_STEPS, null, stepCv)
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Saved skill '${skill.id}' (v${skill.version}, type=${skill.type}) to DB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving skill ${skill.id}", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    fun getSkill(id: String): PersistedSkill? {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.query(
                SkillDatabaseHelper.TABLE_SKILLS,
                null,
                "id = ?",
                arrayOf(id),
                null,
                null,
                null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val skill = cursorToSkill(it)
                    val steps = getSkillSteps(id)
                    skill.copy(steps = steps)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading skill $id", e)
            null
        }
    }

    fun getAllSkills(activeOnly: Boolean = false): List<PersistedSkill> {
        val list = mutableListOf<PersistedSkill>()
        return try {
            val wdb = dbHelper.writableDatabase
            runCatching {
                wdb.execSQL("DELETE FROM ${SkillDatabaseHelper.TABLE_SKILLS} WHERE id IN (SELECT skill_id FROM ${SkillDatabaseHelper.TABLE_SKILL_STEPS} WHERE action_type = 'TOOL')")
                wdb.execSQL("DELETE FROM ${SkillDatabaseHelper.TABLE_SKILL_STEPS} WHERE action_type = 'TOOL'")
            }
            val db = dbHelper.readableDatabase
            val selection = if (activeOnly) "status != 'DEPRECATED'" else null
            val cursor = db.query(
                SkillDatabaseHelper.TABLE_SKILLS,
                null,
                selection,
                null,
                null,
                null,
                "updated_at DESC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    val skill = cursorToSkill(it)
                    list.add(skill)
                }
            }
            // Populate steps
            list.map { skill ->
                val steps = getSkillSteps(skill.id)
                skill.copy(steps = steps)
            }.filter { skill ->
                skill.steps.all { it.actionType != "TOOL" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading all skills", e)
            emptyList()
        }
    }

    fun deleteSkill(id: String): Boolean {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        return try {
            db.delete(SkillDatabaseHelper.TABLE_SKILLS, "id = ?", arrayOf(id))
            db.delete(SkillDatabaseHelper.TABLE_SKILL_STEPS, "skill_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting skill $id", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    private fun getSkillSteps(skillId: String): List<PersistedSkillStep> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<PersistedSkillStep>()
        try {
            val cursor = db.query(
                SkillDatabaseHelper.TABLE_SKILL_STEPS,
                null,
                "skill_id = ?",
                arrayOf(skillId),
                null,
                null,
                "step_index ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    val index = it.getInt(it.getColumnIndexOrThrow("step_index"))
                    val action = it.getString(it.getColumnIndexOrThrow("action_type"))
                    val paramsJson = it.getString(it.getColumnIndexOrThrow("params_json"))
                    val params = parseParamsJson(paramsJson)
                    list.add(PersistedSkillStep(index, action, params))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting skill steps for $skillId", e)
        }
        return list
    }

    // --- WebApps CRUD ---

    fun saveWebApp(app: PersistedWebApp): Boolean {
        val db = dbHelper.writableDatabase
        return try {
            val cv = ContentValues().apply {
                put("id", app.id)
                put("name", app.name)
                put("description", app.description)
                put("path", app.path)
                put("port", app.port)
                put("entry_point", app.entryPoint)
                put("url", app.url)
                put("status", app.status)
                put("theme", app.theme)
                put("created_at", app.createdAt)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                SkillDatabaseHelper.TABLE_WEBAPPS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            Log.d(TAG, "Saved webapp '${app.id}' to DB: ${app.url}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving webapp ${app.id}", e)
            false
        }
    }

    fun getWebApp(id: String): PersistedWebApp? {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.query(
                SkillDatabaseHelper.TABLE_WEBAPPS,
                null,
                "id = ?",
                arrayOf(id),
                null,
                null,
                null
            )
            cursor.use {
                if (it.moveToFirst()) cursorToWebApp(it) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading webapp $id", e)
            null
        }
    }

    fun getAllWebApps(): List<PersistedWebApp> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<PersistedWebApp>()
        return try {
            val cursor = db.query(
                SkillDatabaseHelper.TABLE_WEBAPPS,
                null,
                null,
                null,
                null,
                null,
                "updated_at DESC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToWebApp(it))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error reading all webapps", e)
            emptyList()
        }
    }

    fun deleteWebApp(id: String): Boolean {
        val db = dbHelper.writableDatabase
        return try {
            db.delete(SkillDatabaseHelper.TABLE_WEBAPPS, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting webapp $id", e)
            false
        }
    }

    // --- Helpers ---

    private fun cursorToSkill(c: Cursor): PersistedSkill {
        val id = c.getString(c.getColumnIndexOrThrow("id"))
        val name = c.getString(c.getColumnIndexOrThrow("name"))
        val description = c.getString(c.getColumnIndexOrThrow("description"))
        val triggersJson = c.getString(c.getColumnIndexOrThrow("triggers_json"))
        val typeStr = c.getString(c.getColumnIndexOrThrow("type"))
        val version = c.getInt(c.getColumnIndexOrThrow("version"))
        val confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"))
        val status = c.getString(c.getColumnIndexOrThrow("status"))
        val riskStr = c.getString(c.getColumnIndexOrThrow("risk_level"))
        val configJson = c.getString(c.getColumnIndexOrThrow("config_json"))
        val createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        val updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))

        val triggers = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(triggersJson)
            for (i in 0 until arr.length()) {
                triggers.add(arr.getString(i))
            }
        }

        return PersistedSkill(
            id = id,
            name = name,
            description = description,
            triggers = triggers,
            type = runCatching { PersistedSkillType.valueOf(typeStr) }.getOrDefault(PersistedSkillType.DYNAMIC),
            version = version,
            confidence = confidence,
            status = status,
            riskLevel = runCatching { RiskLevel.valueOf(riskStr) }.getOrDefault(RiskLevel.LOW),
            configJson = configJson,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun cursorToWebApp(c: Cursor): PersistedWebApp {
        return PersistedWebApp(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            description = c.getString(c.getColumnIndexOrThrow("description")),
            path = c.getString(c.getColumnIndexOrThrow("path")),
            port = c.getInt(c.getColumnIndexOrThrow("port")),
            entryPoint = c.getString(c.getColumnIndexOrThrow("entry_point")),
            url = c.getString(c.getColumnIndexOrThrow("url")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            theme = c.getString(c.getColumnIndexOrThrow("theme")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
        )
    }

    private fun parseParamsJson(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
        }
        return map
    }
}
