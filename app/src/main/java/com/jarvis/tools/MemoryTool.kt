package com.jarvis.tools

import com.jarvis.memory.MemoryStore
import com.jarvis.memory.MemoryType

class NoteTool(private val memoryStore: MemoryStore? = null) : Tool {
    override val name: String = "NOTE"
    override val description: String = "Saves facts, notes, or user preferences to local persistent memory. Parameters: key (identifier/topic), value (content to remember)."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val key = params["key"]?.trim().orEmpty()
        val value = params["value"]?.trim().orEmpty()
        if (key.isBlank()) return ToolResult(false, "Note key missing")
        if (value.isBlank()) return ToolResult(false, "Note value missing")
        if (com.jarvis.foundation.SensitiveTerms.contains(key) || com.jarvis.foundation.SensitiveTerms.contains(value)) {
            return ToolResult(false, "I won't remember that: sensitive content (passwords, personal numbers) is never stored.")
        }
        val store = memoryStore ?: return ToolResult(true, "Saved memory: $key = $value (test mode)")
        store.getPreferences(64)
            .filter { it.key.equals(key, ignoreCase = true) }
            .forEach { store.archiveMemory(it.id) }
        val saved = store.saveMemory(
            type = MemoryType.USER_PREFERENCE,
            key = key,
            content = value,
            provenance = "NOTE_TOOL",
            importance = 0.85
        )
        return if (saved > 0) {
            ToolResult(true, "Saved memory: $key = $value")
        } else {
            ToolResult(false, "I won't remember that: sensitive content (passwords, personal numbers) is never stored.")
        }
    }
}

class SearchMemoryTool(private val memoryStore: MemoryStore? = null) : Tool {
    override val name: String = "SEARCH_MEMORY"
    override val description: String = "Searches persistent local memory and user notes. Parameter: query (search keywords)."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim().orEmpty()
        if (query.isBlank()) return ToolResult(false, "Search query missing")
        val store = memoryStore ?: return ToolResult(true, "No matching memories found.")
        val result = store.queryRelevantMemories(
            query = query,
            types = MemoryType.values().toSet(),
            limit = 8
        )
        return ToolResult(
            true,
            if (result.isEmpty()) "No matching memories found."
            else result.joinToString("\n") { "- ${it.key}: ${it.content} (source=${it.provenance})" }
        )
    }
}