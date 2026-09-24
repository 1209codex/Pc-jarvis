package com.jarvis.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseContext(base: Context, private val dbDirectory: File) : ContextWrapper(base) {
    override fun getDatabasePath(name: String): File {
        return try {
            if (JarvisStorageHub.isDirWritable(dbDirectory)) {
                val dbFile = if (name.startsWith("/")) File(name) else File(dbDirectory, name)
                dbFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                if (dbFile.exists() && !dbFile.canWrite()) {
                    Log.w("DatabaseContext", "Database file $dbFile is not writable, falling back to baseContext")
                    baseContext.getDatabasePath(name)
                } else {
                    dbFile
                }
            } else {
                baseContext.getDatabasePath(name)
            }
        } catch (e: Exception) {
            Log.w("DatabaseContext", "Error determining database path for $name (${e.message}), falling back to baseContext")
            baseContext.getDatabasePath(name)
        }
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        return try {
            val path = getDatabasePath(name)
            SQLiteDatabase.openOrCreateDatabase(path, factory)
        } catch (e: Exception) {
            Log.w("DatabaseContext", "Failed to open database at $name (${e.message}), falling back to baseContext")
            baseContext.openOrCreateDatabase(name, mode, factory)
        }
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        return try {
            val path = getDatabasePath(name)
            SQLiteDatabase.openOrCreateDatabase(path.path, factory, errorHandler)
        } catch (e: Exception) {
            Log.w("DatabaseContext", "Failed to open database at $name with errorHandler (${e.message}), falling back to baseContext")
            baseContext.openOrCreateDatabase(name, mode, factory, errorHandler)
        }
    }
}

object JarvisStorageHub {
    private const val TAG = "JarvisStorageHub"
    const val ROOT_DIR_NAME = "jarvis-db"

    @Volatile
    private var isInitialized = false

    fun isDirWritable(dir: File): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
                    if (!canonical.startsWith("/data/") && !canonical.contains("/Android/data/")) {
                        return false
                    }
                }
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (!dir.exists() || !dir.canRead() || !dir.canWrite()) {
                return false
            }
            val probe = File(dir, ".probe_${System.currentTimeMillis()}")
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the primary root directory for all JARVIS persistent data:
     * Priority:
     * 1. /storage/emulated/0/jarvis-db (only if MANAGE_EXTERNAL_STORAGE is granted and writable)
     * 2. App-specific external storage: Android/data/com.jarvis/files/jarvis-db (Scoped storage safe)
     * 3. App internal storage: data/data/com.jarvis/files/jarvis-db
     */
    fun getRootDirectory(context: Context? = null): File {
        val hasAllFilesAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        if (hasAllFilesAccess) {
            val direct = File("/storage/emulated/0", ROOT_DIR_NAME)
            if (isDirWritable(direct)) return direct

            val ext = File(Environment.getExternalStorageDirectory(), ROOT_DIR_NAME)
            if (isDirWritable(ext)) return ext

            val sdcard = File("/sdcard", ROOT_DIR_NAME)
            if (isDirWritable(sdcard)) return sdcard
        }

        // Safe Fallback 1: App's standard external files dir (Scoped storage safe, accessible via USB)
        if (context != null) {
            try {
                val appExternal = context.getExternalFilesDir(null)
                if (appExternal != null) {
                    val hubDir = File(appExternal, ROOT_DIR_NAME)
                    if (isDirWritable(hubDir)) return hubDir
                }
            } catch (_: Exception) {}

            // Safe Fallback 2: App's internal storage
            try {
                val appInternal = File(context.filesDir, ROOT_DIR_NAME)
                if (!appInternal.exists()) appInternal.mkdirs()
                if (appInternal.exists()) return appInternal
            } catch (_: Exception) {}
        }

        return File("/storage/emulated/0", ROOT_DIR_NAME)
    }

    fun getDatabasesDirectory(context: Context? = null): File =
        File(getRootDirectory(context), "databases").apply { if (!exists()) mkdirs() }

    fun getVectorDbDirectory(context: Context? = null): File =
        File(getRootDirectory(context), "vector-db").apply { if (!exists()) mkdirs() }

    fun getReportsDirectory(context: Context? = null): File =
        File(getRootDirectory(context), "reports").apply { if (!exists()) mkdirs() }

    fun getVaultDirectory(context: Context? = null): File =
        File(getRootDirectory(context), "vault").apply { if (!exists()) mkdirs() }

    fun getLogsDirectory(context: Context? = null): File =
        File(getRootDirectory(context), "logs").apply { if (!exists()) mkdirs() }

    fun createDatabaseContext(context: Context): Context {
        initStorage(context)
        val dbDir = getDatabasesDirectory(context)
        return DatabaseContext(context, dbDir)
    }

    /**
     * Initializes the entire directory hierarchy and writes manifest.json with date and statistics.
     */
    fun initStorage(context: Context) {
        if (isInitialized) return

        try {
            val root = getRootDirectory(context)
            getDatabasesDirectory(context)
            getVectorDbDirectory(context)
            getReportsDirectory(context)
            getVaultDirectory(context)
            getLogsDirectory(context)

            migrateInternalDatabasesIfNeeded(context)
            try {
                updateManifest(context)
            } catch (e: Exception) {
                Log.w(TAG, "Manifest update notice: ${e.message}")
            }

            isInitialized = true
            Log.i(TAG, "JarvisStorageHub initialized successfully at: ${root.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize JarvisStorageHub: ${e.message}", e)
        }
    }

    private fun migrateInternalDatabasesIfNeeded(context: Context) {
        try {
            val internalDbDir = context.getDatabasePath("dummy").parentFile ?: return
            if (!internalDbDir.exists()) return

            val targetDir = getDatabasesDirectory(context)
            val files = internalDbDir.listFiles() ?: return

            for (file in files) {
                if (file.isFile && (file.name.endsWith(".db") || file.name.endsWith(".db-wal") || file.name.endsWith(".db-shm"))) {
                    val dest = File(targetDir, file.name)
                    if (!dest.exists()) {
                        file.copyTo(dest, overwrite = false)
                        Log.i(TAG, "Migrated database file ${file.name} to jarvis-db/databases/")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Database migration notice: ${e.message}")
        }
    }

    fun updateManifest(context: Context): File {
        val root = getRootDirectory(context)
        val manifestFile = File(root, "jarvis_manifest.json")
        val todayStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

        val dbFiles = getDatabasesDirectory(context).listFiles()?.filter { it.name.endsWith(".db") }?.map { it.name } ?: emptyList()
        val vectorFiles = getVectorDbDirectory(context).listFiles()?.map { it.name } ?: emptyList()
        val reportFolders = getReportsDirectory(context).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

        val json = JSONObject().apply {
            put("hub_name", "JARVIS Central Local Storage Hub")
            put("root_path", root.absolutePath)
            put("created_at", todayStr)
            put("last_sync_date", todayStr)
            put("status", "ONLINE_ACTIVE")
            put("databases", JSONObject().apply {
                put("directory", "databases/")
                put("total_databases", dbFiles.size)
                put("database_files", org.json.JSONArray(dbFiles))
            })
            put("vector_db", JSONObject().apply {
                put("directory", "vector-db/")
                put("total_files", vectorFiles.size)
                put("files", org.json.JSONArray(vectorFiles))
            })
            put("reports", JSONObject().apply {
                put("directory", "reports/")
                put("total_reports", reportFolders.size)
                put("folders", org.json.JSONArray(reportFolders))
            })
            put("vault", JSONObject().apply {
                put("directory", "vault/")
            })
            put("logs", JSONObject().apply {
                put("directory", "logs/")
            })
        }

        manifestFile.writeText(json.toString(2), Charsets.UTF_8)
        return manifestFile
    }

    fun getStorageOverview(context: Context): Map<String, Any> {
        val root = getRootDirectory(context)
        val dbCount = getDatabasesDirectory(context).listFiles()?.count { it.name.endsWith(".db") } ?: 0
        val reportCount = getReportsDirectory(context).listFiles()?.count { it.isDirectory } ?: 0
        val totalBytes = calculateDirSize(root)

        return mapOf(
            "root_path" to root.absolutePath,
            "total_databases" to dbCount,
            "total_reports" to reportCount,
            "total_size_bytes" to totalBytes,
            "formatted_size" to formatBytes(totalBytes)
        )
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.ROOT, "%.2f MB", mb)
        }
    }
}
