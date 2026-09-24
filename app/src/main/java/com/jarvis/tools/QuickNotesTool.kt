package com.jarvis.tools

import android.content.Context
import android.util.Log
import com.jarvis.foundation.RiskLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smart Voice Notes, Lists & Ideas Tool.
 */
class QuickNotesTool(
    private val context: Context? = null,
    private val customIndexStore: com.jarvis.rag.IRagIndexStore? = null
) : Tool {
    private val TAG = "QuickNotesTool"

    override val name: String = "QUICK_NOTES"
    override val description: String =
        "Creates, searches, lists, and manages voice notes and checklists. Actions: 'add' (content, title, tag), 'list' (tag), 'search' (query), 'delete' (id), 'clear'."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.LOW)

    private val notesFile: File by lazy {
        val parent = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        File(parent, "quick_notes.json")
    }

    private data class NoteItem(
        val id: String,
        val title: String,
        val content: String,
        val tag: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("content", content)
            put("tag", tag)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(json: JSONObject): NoteItem = NoteItem(
                id = json.optString("id", System.currentTimeMillis().toString()),
                title = json.optString("title", "Untitled Note"),
                content = json.optString("content", ""),
                tag = json.optString("tag", "general"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    @Synchronized
    private fun loadNotes(): MutableList<NoteItem> {
        if (!notesFile.exists()) return mutableListOf()
        return try {
            val text = notesFile.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            val list = mutableListOf<NoteItem>()
            for (i in 0 until arr.length()) {
                list.add(NoteItem.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Error loading notes: ${e.message}")
            mutableListOf()
        }
    }

    @Synchronized
    private fun saveNotes(notes: List<NoteItem>) {
        try {
            val arr = JSONArray()
            notes.forEach { arr.put(it.toJson()) }
            notesFile.writeText(arr.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Error saving notes: ${e.message}")
        }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "add"

        return when (action) {
            "add", "create", "write", "save" -> {
                val content = params["content"] ?: params["text"] ?: params["note"] ?: ""
                if (content.isBlank()) return ToolResult.Failed("Missing note content")

                val title = params["title"]?.trim()?.ifBlank { "Note" } ?: "Note"
                val tag = params["tag"]?.trim()?.removePrefix("#")?.lowercase()?.ifBlank { "general" } ?: "general"
                val noteId = "note_${System.currentTimeMillis()}"

                val note = NoteItem(
                    id = noteId,
                    title = title,
                    content = content,
                    tag = tag,
                    timestamp = System.currentTimeMillis()
                )

                val all = loadNotes()
                all.add(0, note)
                saveNotes(all)

                // Seamlessly index note into local RAG Knowledge Vault for semantic retrieval
                var indexedInRag = false
                runCatching {
                    val ragStore: com.jarvis.rag.IRagIndexStore? = customIndexStore ?: context?.let { com.jarvis.rag.RagIndexStore(it) }
                    if (ragStore != null) {
                        val chunker = com.jarvis.rag.DocumentChunker()
                        val (meta, chunks) = chunker.chunk(
                            rawText = "$title: $content #$tag",
                            docId = noteId,
                            title = title,
                            sourcePath = "quick_notes"
                        )
                        ragStore.indexDocument(meta, chunks)
                        indexedInRag = true
                        Log.i(TAG, "Indexed note #$noteId into RAG Knowledge Vault")
                    }
                }

                val ragSuffix = if (indexedInRag) " Indexed in RAG vault." else ""
                Log.i(TAG, "Saved note #$noteId [$tag]: '$title' - '$content'")
                ToolResult.Success(
                    message = "Note saved: \"$content\" (Tag: #$tag).$ragSuffix",
                    data = mapOf("id" to noteId, "title" to title, "content" to content, "tag" to tag)
                )
            }

            "list", "get_all", "show" -> {
                val filterTag = params["tag"]?.trim()?.removePrefix("#")?.lowercase()
                val all = loadNotes()
                val filtered = if (!filterTag.isNullOrBlank()) all.filter { it.tag.equals(filterTag, ignoreCase = true) } else all

                if (filtered.isEmpty()) {
                    val msg = if (!filterTag.isNullOrBlank()) "No notes found with tag #$filterTag." else "No notes saved yet."
                    ToolResult.Success(msg, mapOf("count" to 0))
                } else {
                    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    val formatted = filtered.take(10).mapIndexed { i, n ->
                        val dateStr = sdf.format(Date(n.timestamp))
                        "${i + 1}. [${n.tag.uppercase()}] ${n.title}: ${n.content} ($dateStr)"
                    }.joinToString("\n")

                    ToolResult.Success(
                        message = "Saved Notes (${filtered.size}):\n$formatted",
                        data = mapOf("count" to filtered.size)
                    )
                }
            }

            "search", "find" -> {
                val query = params["query"]?.trim()?.lowercase() ?: return ToolResult.Failed("Missing search query")
                val all = loadNotes()
                val matched = all.filter {
                    it.content.lowercase().contains(query) ||
                    it.title.lowercase().contains(query) ||
                    it.tag.lowercase().contains(query)
                }

                if (matched.isEmpty()) {
                    ToolResult.Success("No notes found matching '$query'.", mapOf("count" to 0))
                } else {
                    val formatted = matched.take(5).mapIndexed { i, n ->
                        "${i + 1}. [${n.tag}] ${n.content}"
                    }.joinToString("\n")
                    ToolResult.Success(
                        message = "Found ${matched.size} note(s) for '$query':\n$formatted",
                        data = mapOf("count" to matched.size)
                    )
                }
            }

            "delete", "remove" -> {
                val id = params["id"]?.trim() ?: params["note_id"]?.trim() ?: return ToolResult.Failed("Missing note id")
                val all = loadNotes()
                val removed = all.removeAll { it.id == id }
                if (removed) {
                    saveNotes(all)
                    ToolResult.Success("Deleted note '$id'")
                } else {
                    ToolResult.Failed("Note '$id' not found")
                }
            }

            "clear", "delete_all" -> {
                saveNotes(emptyList())
                ToolResult.Success("Cleared all notes.")
            }

            else -> ToolResult.Failed("Unknown notes action '$action'")
        }
    }
}
