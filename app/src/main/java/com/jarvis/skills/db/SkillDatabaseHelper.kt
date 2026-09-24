package com.jarvis.skills.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite Database Helper for persisting skills, procedural workflows, and web application records.
 * Database: jarvis_skills.db
 */
class SkillDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "SkillDatabaseHelper"
        const val DATABASE_NAME = "jarvis_skills.db"
        const val DATABASE_VERSION = 1

        const val TABLE_SKILLS = "skills"
        const val TABLE_SKILL_STEPS = "skill_steps"
        const val TABLE_WEBAPPS = "webapps"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SKILLS (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    triggers_json TEXT NOT NULL,
                    type TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    risk_level TEXT NOT NULL DEFAULT 'READ_ONLY',
                    config_json TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_skills_type ON $TABLE_SKILLS(type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_skills_status ON $TABLE_SKILLS(status)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SKILL_STEPS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    skill_id TEXT NOT NULL,
                    step_index INTEGER NOT NULL,
                    action_type TEXT NOT NULL,
                    params_json TEXT NOT NULL DEFAULT '{}',
                    FOREIGN KEY(skill_id) REFERENCES $TABLE_SKILLS(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_steps_skill ON $TABLE_SKILL_STEPS(skill_id, step_index)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_WEBAPPS (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    path TEXT NOT NULL,
                    port INTEGER NOT NULL DEFAULT 8888,
                    entry_point TEXT NOT NULL DEFAULT 'index.html',
                    url TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'READY',
                    theme TEXT NOT NULL DEFAULT 'JARVIS_HUD',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_webapps_status ON $TABLE_WEBAPPS(status)")

            Log.i(TAG, "Skill database tables created successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating skill database tables", e)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createTables(db)
    }
}
