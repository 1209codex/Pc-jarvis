package com.jarvis.tools

import android.content.Context
import com.jarvis.files.FileManager
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class FileManagerTool(
    private val context: Context? = null,
    private val fileManager: FileManager = FileManager(context)
) : Tool {

    override val name: String = "FILE_MANAGER"
    override val description: String =
        "Manages storage directories and files. Actions: create_file, write_file, make_file, list_directory, list_files, list_folders, read_text_file, search_file, delete_file, storage_breakdown, cleanup_suggestions. Parameters: action, filename/file/path, content, append, query."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "FILE_MANAGER",
        description = "Manages file creation, directory inspection, storage health, and document reading.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "storage_breakdown"
        val target = params["path"]?.trim().takeUnless { it.isNullOrBlank() }
            ?: params["filename"]?.trim().takeUnless { it.isNullOrBlank() }
            ?: params["file"]?.trim().takeUnless { it.isNullOrBlank() }
            ?: params["query"]?.trim()
            ?: ""
        val content = params["content"] ?: params["text"] ?: ""
        val append = params["append"]?.toBoolean() ?: false
        val query = params["query"] ?: ""

        when (action) {
            "create_file", "write_file", "make_file", "create", "write" -> {
                if (target.isBlank()) {
                    return ToolResult.Failed("Please specify a filename or path to create.")
                }
                val result = fileManager.createFile(target, content, append)
                return if (result.success) {
                    ToolResult.Success(
                        message = result.message,
                        data = mapOf("path" to result.path, "sizeBytes" to result.sizeBytes)
                    )
                } else {
                    ToolResult.Failed(result.message)
                }
            }

            "list_directory", "list_files", "list_folders", "list_dir", "ls" -> {
                val listResult = fileManager.listDirectory(target.ifBlank { null }, query = query)
                return ToolResult.Success(
                    message = listResult.message,
                    data = mapOf(
                        "path" to listResult.currentPath,
                        "total" to listResult.totalItems,
                        "folders_count" to listResult.folders.size,
                        "files_count" to listResult.files.size
                    )
                )
            }

            "delete_file", "delete", "remove_file" -> {
                if (target.isBlank()) {
                    return ToolResult.Failed("Please specify the file to delete.")
                }
                val result = fileManager.deleteFile(target)
                return if (result.success) {
                    ToolResult.Success(message = result.message, data = mapOf("path" to result.path))
                } else {
                    ToolResult.Failed(result.message)
                }
            }

            "search_file", "find_file", "search" -> {
                if (target.isBlank()) {
                    return ToolResult.Failed("Please specify a filename or search query.")
                }
                val matches = fileManager.searchFiles(target, maxResults = 8)
                if (matches.isEmpty()) {
                    return ToolResult.Success(
                        message = "No files found matching '$target' in Downloads or Documents.",
                        data = mapOf("count" to 0)
                    )
                }
                val summary = matches.joinToString("\n") {
                    val size = fileManager.formatBytes(it.sizeBytes)
                    "• ${it.name} ($size) - ${it.path}"
                }
                return ToolResult.Success(
                    message = "Found ${matches.size} file(s) matching '$target':\n$summary",
                    data = mapOf("count" to matches.size, "first_path" to matches.first().path)
                )
            }

            "storage_breakdown", "storage", "check_storage" -> {
                val stats = fileManager.getStorageBreakdown()
                val totalStr = fileManager.formatBytes(stats.totalBytes)
                val usedStr = fileManager.formatBytes(stats.usedBytes)
                val freeStr = fileManager.formatBytes(stats.freeBytes)
                val usedPercent = if (stats.totalBytes > 0) ((stats.usedBytes.toDouble() / stats.totalBytes) * 100).toInt() else 0

                val summary = "Storage Breakdown:\n" +
                        "• Total Storage: $totalStr\n" +
                        "• Used Storage: $usedStr ($usedPercent%)\n" +
                        "• Free Space: $freeStr available\n" +
                        "• Large Files Found: ${stats.largeFiles.size}\n" +
                        "• Redundant APKs Found: ${stats.apkFiles.size}"

                return ToolResult.Success(
                    message = summary,
                    data = mapOf("total" to totalStr, "free" to freeStr, "used_percent" to usedPercent)
                )
            }

            "cleanup_suggestions", "clean_storage", "cleanup" -> {
                val stats = fileManager.getStorageBreakdown()
                val suggestions = mutableListOf<String>()

                if (stats.apkFiles.isNotEmpty()) {
                    suggestions.add("Leftover APK Installers (${stats.apkFiles.size} found):\n" +
                            stats.apkFiles.joinToString("\n") { "  - ${it.name} (${fileManager.formatBytes(it.sizeBytes)})" })
                }
                if (stats.largeFiles.isNotEmpty()) {
                    suggestions.add("Large Files (>50MB):\n" +
                            stats.largeFiles.joinToString("\n") { "  - ${it.name} (${fileManager.formatBytes(it.sizeBytes)})" })
                }

                if (suggestions.isEmpty()) {
                    return ToolResult.Success(
                        message = "Your storage is clean! No redundant APKs or excessive large downloads detected.",
                        data = mapOf("actionable_items" to 0)
                    )
                }

                return ToolResult.Success(
                    message = "Cleanup Suggestions:\n" + suggestions.joinToString("\n\n"),
                    data = mapOf("actionable_items" to stats.apkFiles.size + stats.largeFiles.size)
                )
            }

            "read_text_file", "read_file", "read_document" -> {
                if (target.isBlank()) {
                    return ToolResult.Failed("Please provide a filename or path to read.")
                }
                val fileContent = fileManager.readTextFile(target)
                return ToolResult.Success(
                    message = "Contents of $target:\n\n$fileContent",
                    data = mapOf("path" to target, "length" to fileContent.length)
                )
            }

            else -> return ToolResult.Failed("Unknown file action: '$action'. Supported: create_file, list_directory, read_text_file, search_file, delete_file, storage_breakdown, cleanup_suggestions.")
        }
    }
}
