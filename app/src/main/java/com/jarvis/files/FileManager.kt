package com.jarvis.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

data class FileSearchResult(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

data class StorageBreakdown(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val largeFiles: List<FileSearchResult>,
    val apkFiles: List<FileSearchResult>
)

data class DirectoryItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

data class DirectoryListResult(
    val currentPath: String,
    val totalItems: Int,
    val folders: List<DirectoryItem>,
    val files: List<DirectoryItem>,
    val message: String
)

data class FileActionResult(
    val success: Boolean,
    val path: String,
    val sizeBytes: Long,
    val message: String
)

class FileManager(private val context: Context? = null) {

    fun resolveFile(target: String): File {
        val trimmed = target.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            val cleanPath = trimmed.removePrefix("file://")
            return File(cleanPath)
        }
        if (trimmed.startsWith("jarvis-db/") || trimmed.startsWith("jarvis_db/")) {
            val root = com.jarvis.storage.JarvisStorageHub.getRootDirectory(context)
            val subPath = trimmed.substringAfter("/")
            return File(root, subPath)
        }
        val baseDir = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull()
            ?.takeIf { it.exists() || it.mkdirs() }
            ?: runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
            ?: context?.getExternalFilesDir(null)
            ?: context?.filesDir
            ?: File("/sdcard/Documents")

        return File(baseDir, trimmed)
    }

    fun createFile(target: String, content: String, append: Boolean = false): FileActionResult {
        return try {
            val file = resolveFile(target)
            if (!isPathAllowed(file)) {
                return FileActionResult(false, file.absolutePath, 0L, "Access Denied: Path is outside allowed user storage: ${file.absolutePath}")
            }
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (append && file.exists()) {
                file.appendText(content, Charsets.UTF_8)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            FileActionResult(
                success = true,
                path = file.absolutePath,
                sizeBytes = file.length(),
                message = "Created file '${file.name}' (${formatBytes(file.length())}) at ${file.absolutePath}"
            )
        } catch (e: Exception) {
            FileActionResult(false, target, 0L, "Failed to create file: ${e.message}")
        }
    }

    fun listDirectory(dirPath: String? = null, query: String = "", includeHidden: Boolean = false): DirectoryListResult {
        val targetDir = if (dirPath.isNullOrBlank()) {
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull()
                ?.takeIf { it.exists() }
                ?: runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
                ?: context?.getExternalFilesDir(null)
                ?: File("/sdcard/Documents")
        } else {
            resolveFile(dirPath)
        }

        if (!targetDir.exists() || !targetDir.isDirectory) {
            return DirectoryListResult(
                currentPath = targetDir.absolutePath,
                totalItems = 0,
                folders = emptyList(),
                files = emptyList(),
                message = "Directory does not exist or is not a folder: ${targetDir.absolutePath}"
            )
        }

        val children = targetDir.listFiles() ?: emptyArray()
        val cleanQuery = query.trim().lowercase(Locale.ROOT)

        val folders = mutableListOf<DirectoryItem>()
        val files = mutableListOf<DirectoryItem>()

        for (item in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            if (!includeHidden && item.name.startsWith(".")) continue
            if (cleanQuery.isNotEmpty() && !item.name.lowercase(Locale.ROOT).contains(cleanQuery)) continue

            val dItem = DirectoryItem(
                name = item.name,
                path = item.absolutePath,
                isDirectory = item.isDirectory,
                sizeBytes = if (item.isDirectory) 0L else item.length(),
                lastModified = item.lastModified()
            )
            if (item.isDirectory) {
                folders.add(dItem)
            } else {
                files.add(dItem)
            }
        }

        val total = folders.size + files.size
        val summary = "Directory '${targetDir.name}' (${targetDir.absolutePath}):\n" +
                "• Folders (${folders.size}): " + (if (folders.isEmpty()) "none" else folders.joinToString(", ") { it.name }) + "\n" +
                "• Files (${files.size}): " + (if (files.isEmpty()) "none" else files.joinToString(", ") { "${it.name} (${formatBytes(it.sizeBytes)})" })

        return DirectoryListResult(
            currentPath = targetDir.absolutePath,
            totalItems = total,
            folders = folders,
            files = files,
            message = summary
        )
    }

    fun deleteFile(target: String): FileActionResult {
        return try {
            val file = resolveFile(target)
            if (!isPathAllowed(file)) {
                return FileActionResult(false, file.absolutePath, 0L, "Access Denied: Path not allowed")
            }
            if (!file.exists()) {
                return FileActionResult(false, file.absolutePath, 0L, "File not found: ${file.name}")
            }
            val deleted = file.delete()
            if (deleted) {
                FileActionResult(true, file.absolutePath, 0L, "Deleted '${file.name}' successfully")
            } else {
                FileActionResult(false, file.absolutePath, 0L, "Failed to delete '${file.name}'")
            }
        } catch (e: Exception) {
            FileActionResult(false, target, 0L, "Error deleting file: ${e.message}")
        }
    }

    fun searchFiles(query: String, maxResults: Int = 10): List<FileSearchResult> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val results = mutableListOf<FileSearchResult>()

        val searchDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull(),
            context?.getExternalFilesDir(null),
            context?.filesDir
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                traverseAndSearch(dir, cleanQuery, results, maxResults, currentDepth = 0)
            }
            if (results.size >= maxResults) break
        }

        return results
    }

    private fun traverseAndSearch(dir: File, query: String, results: MutableList<FileSearchResult>, maxResults: Int, currentDepth: Int) {
        if (currentDepth > 4 || results.size >= maxResults) return
        val children = dir.listFiles() ?: return

        for (file in children) {
            if (file.name.startsWith(".")) continue
            if (query.isBlank() || file.name.lowercase(Locale.ROOT).contains(query)) {
                results.add(
                    FileSearchResult(
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        isDirectory = file.isDirectory
                    )
                )
            }
            if (results.size >= maxResults) return
            if (file.isDirectory) {
                traverseAndSearch(file, query, results, maxResults, currentDepth + 1)
            }
        }
    }

    fun getStorageBreakdown(): StorageBreakdown {
        val path = runCatching { Environment.getDataDirectory() }.getOrNull()
        val (total, free, used) = try {
            if (path != null && path.exists()) {
                val stat = StatFs(path.path)
                val blockSize = stat.blockSizeLong
                val totalBlocks = stat.blockCountLong
                val availableBlocks = stat.availableBlocksLong
                val t = totalBlocks * blockSize
                val f = availableBlocks * blockSize
                Triple(t, f, t - f)
            } else {
                Triple(-1L, -1L, -1L)
            }
        } catch (e: Exception) {
            Triple(-1L, -1L, -1L)
        }

        val largeFiles = mutableListOf<FileSearchResult>()
        val apkFiles = mutableListOf<FileSearchResult>()

        val downloads = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        if (downloads != null && downloads.exists()) {
            val list = downloads.listFiles() ?: emptyArray()
            for (f in list) {
                if (f.isFile && f.length() > 50 * 1024 * 1024L) { // >50MB
                    largeFiles.add(FileSearchResult(f.name, f.absolutePath, f.length(), f.lastModified(), false))
                }
                if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                    apkFiles.add(FileSearchResult(f.name, f.absolutePath, f.length(), f.lastModified(), false))
                }
            }
        }

        return StorageBreakdown(
            totalBytes = total,
            freeBytes = free,
            usedBytes = used,
            largeFiles = largeFiles.take(5),
            apkFiles = apkFiles.take(5)
        )
    }

    fun isPathAllowed(file: File): Boolean {
        val canonical = try { file.canonicalFile } catch (_: Exception) { return false }
        val canonicalPath = canonical.path

        val forbiddenSubstrings = listOf(
            "/data/data/",
            "/data/user/",
            "shared_prefs",
            "databases",
            "files/keys",
            ".ssh",
            ".gnupg"
        )
        val allowedRoots = listOfNotNull(
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull(),
            context?.getExternalFilesDir(null),
            context?.filesDir
        ).mapNotNull { runCatching { it.canonicalFile.path }.getOrNull() }

        val appFilesRoot = context?.filesDir?.canonicalPath
        if (forbiddenSubstrings.any { canonicalPath.contains(it) && (appFilesRoot == null || !canonicalPath.startsWith(appFilesRoot)) }) {
            return false
        }

        if (allowedRoots.isNotEmpty()) {
            return allowedRoots.any { canonicalPath.startsWith(it) }
        }
        return !canonicalPath.startsWith("/proc") && !canonicalPath.startsWith("/sys") && !canonicalPath.startsWith("/dev") && !canonicalPath.startsWith("/etc")
    }

    fun readTextFile(path: String, maxChars: Int = 3000): String {
        val file = resolveFile(path)
        if (!isPathAllowed(file)) {
            return "Access Denied: Path is outside allowed user storage or contains protected system data: ${file.absolutePath}"
        }
        if (!file.exists() || !file.canRead() || file.isDirectory) {
            return "File not found or cannot be read: ${file.absolutePath}"
        }
        val sb = StringBuilder(maxChars + 64)
        var totalChars = 0L
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(4096)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                val take = minOf(read, maxChars - sb.length)
                if (take > 0) sb.append(buf, 0, take)
                totalChars += read
                if (sb.length >= maxChars) break
            }
        }
        return if (totalChars > maxChars) {
            sb.toString() + "\n\n...[Truncated remainder of file ($totalChars chars total)]"
        } else {
            sb.toString()
        }
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.ROOT, "%.1f MB", mb)
        }
    }
}
