package com.jarvis.foundation

import android.content.Context
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolResult
import java.io.File

class FileLocateTool(private val context: Context) : Tool {
    override val name: String = "FILE_LOCATE"

    val metadata = ToolMetadata(
        name = name,
        description = "Locates files by filename in app storage",
        parameters = listOf(
            ParameterSchema("filename", "string", "Name or substring of the file to find")
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["filename"] ?: return ToolResult(false, "Filename parameter missing")
        val dir = context.filesDir
        val matches = dir.walkTopDown().filter { it.isFile && it.name.contains(query, ignoreCase = true) }.toList()
        return if (matches.isEmpty()) {
            ToolResult(true, "No files found matching '$query'")
        } else {
            ToolResult(true, "Found files: " + matches.joinToString(", ") { it.name })
        }
    }
}

class FileReadTool(private val context: Context) : Tool {
    override val name: String = "FILE_READ"

    val metadata = ToolMetadata(
        name = name,
        description = "Reads content from a file in app storage",
        parameters = listOf(
            ParameterSchema("filename", "string", "File to read")
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val filename = params["filename"] ?: return ToolResult(false, "Filename missing")
        val file = File(context.filesDir, filename)
        return if (!file.exists()) {
            ToolResult(false, "File '$filename' does not exist")
        } else {
            try {
                val content = file.readText()
                ToolResult(true, content)
            } catch (e: Exception) {
                ToolResult(false, "Failed to read file: ${e.message}")
            }
        }
    }
}

class FileWriteTool(private val context: Context) : Tool {
    override val name: String = "FILE_WRITE"

    val metadata = ToolMetadata(
        name = name,
        description = "Writes content to a file in app storage",
        parameters = listOf(
            ParameterSchema("filename", "string", "Target file name"),
            ParameterSchema("content", "string", "Content to write")
        ),
        riskLevel = RiskLevel.MEDIUM
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val filename = params["filename"] ?: return ToolResult(false, "Filename missing")
        val content = params["content"] ?: return ToolResult(false, "Content missing")
        return try {
            val file = File(context.filesDir, filename)
            file.writeText(content)
            ToolResult(true, "Successfully wrote ${content.length} chars to $filename")
        } catch (e: Exception) {
            ToolResult(false, "Failed to write file: ${e.message}")
        }
    }
}
