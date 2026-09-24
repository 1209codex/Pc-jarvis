package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileSkill : Skill {
    override val id: String = "file_storage_intelligence"
    override val name: String = "File & Storage Assistant Skill"
    override val description: String = "Creates files, lists folders & files, reads documents, inspects storage, and suggests cleanups."
    override val triggers: List<String> = listOf(
        "file", "files", "folder", "folders", "directory", "document", "documents", "pdf",
        "make file", "create file", "write file", "save file", "nano.md",
        "list files", "list folders", "list directory", "read name of files",
        "search file", "find file", "storage", "clean storage", "cleanup",
        "storage space", "storage breakdown", "read file", "delete file"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        return triggers.any { lower.contains(it) } || lower.contains(".md") || lower.contains(".txt") || lower.contains(".json")
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()
        val filenameRegex = Regex("([a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+)")

        // 1. Read File (must come before create_file so "read file nano.md" reads rather than creates)
        if (lower.contains("read file") || lower.contains("open file") || lower.contains("read document") ||
            (lower.startsWith("read ") && !lower.contains("read name")) || lower.contains("show content") || lower.contains("cat ")
        ) {
            val path = filenameRegex.find(goal)?.groupValues?.get(1)
                ?: lower.replace(Regex("\\b(read|open|file|document|show|content|please)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("FILE_MANAGER", mapOf("action" to "read_text_file", "path" to path)),
                explanation = "Reading file: $path"
            )
        }

        // 2. List Directory / Files / Folders
        if (lower.contains("list") || lower.contains("read name") || lower.contains("show files") || lower.contains("show folder") || lower.contains("what files") || lower.contains("files and folders")) {
            val targetFolder = when {
                lower.contains("download") -> "Downloads"
                lower.contains("document") -> "Documents"
                else -> ""
            }
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "FILE_MANAGER",
                    params = mapOf(
                        "action" to "list_directory",
                        "path" to targetFolder
                    )
                ),
                explanation = "Listing files and folders in storage"
            )
        }

        // 3. Delete File
        if (lower.contains("delete") || lower.contains("remove file")) {
            val target = filenameRegex.find(goal)?.groupValues?.get(1)
                ?: lower.replace(Regex("\\b(delete|remove|file|please)\\b", RegexOption.IGNORE_CASE), "").trim()
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "FILE_MANAGER",
                    params = mapOf(
                        "action" to "delete_file",
                        "filename" to target
                    )
                ),
                explanation = "Deleting file '$target'"
            )
        }

        // 4. Storage Cleanup Suggestions
        if (lower.contains("clean") || lower.contains("free up") || lower.contains("cleanup")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("FILE_MANAGER", mapOf("action" to "cleanup_suggestions")),
                explanation = "Analyzing storage for cleanup recommendations"
            )
        }

        // 5. Storage Breakdown
        if (lower.contains("storage") && !lower.contains("find") && !lower.contains("search")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("FILE_MANAGER", mapOf("action" to "storage_breakdown")),
                explanation = "Retrieving device storage statistics"
            )
        }

        // 6. Create / Make / Write File
        if (lower.contains("make") || lower.contains("create") || lower.contains("write") || lower.contains("save") || lower.contains(".md") || lower.contains(".txt")) {
            val foundName = filenameRegex.find(goal)?.groupValues?.get(1)
                ?: if (lower.contains("nano")) "nano.md" else "note.txt"

            val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val content = when {
                lower.contains("date") || lower.contains("time") || lower.contains("today") -> {
                    "# $foundName\n\nDate and Time: $nowFormatted\nCreated by: JARVIS Autonomous System\n"
                }
                lower.contains("content") || lower.contains("text") || lower.contains("that") || lower.contains("with") -> {
                    val afterWith = goal.substringAfter("with ", "").ifBlank {
                        goal.substringAfter("that ", "").ifBlank {
                            goal.substringAfter("content ", "Created by JARVIS on $nowFormatted")
                        }
                    }
                    afterWith.ifBlank { "File created by JARVIS on $nowFormatted\n" }
                }
                else -> {
                    "File: $foundName\nCreated by JARVIS on $nowFormatted\n"
                }
            }

            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "FILE_MANAGER",
                    params = mapOf(
                        "action" to "create_file",
                        "filename" to foundName,
                        "content" to content
                    )
                ),
                explanation = "Creating file '$foundName' with content"
            )
        }

        // 7. Search File
        val query = lower.replace(Regex("\\b(find|search|file|files|document|documents|pdf|my|in|downloads)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val finalQuery = query.ifBlank { "pdf" }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction("FILE_MANAGER", mapOf("action" to "search_file", "query" to finalQuery)),
            explanation = "Searching storage for file: $finalQuery"
        )
    }
}
