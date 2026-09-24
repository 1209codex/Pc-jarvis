package com.jarvis.tools

import com.jarvis.rag.DocumentChunker
import com.jarvis.rag.RagIndexStore
import java.io.File
import java.util.UUID

import com.jarvis.rag.IRagIndexStore

class RagIndexTool(
    private val indexStore: IRagIndexStore,
    private val chunker: DocumentChunker = DocumentChunker()
) : Tool {
    override val name: String = "RAG_INDEX"
    override val description: String =
        "Index a text document, note, or file into the local Knowledge Vault with semantic chunking and BM25/vector indexing. Params: content or path (required), title (optional), doc_id (optional)."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val path = params["path"]?.trim() ?: params["file"]?.trim() ?: ""
        var content = params["content"]?.trim() ?: params["text"]?.trim() ?: params["query"]?.trim() ?: params["data"]?.trim() ?: ""
        var title = params["title"]?.trim() ?: ""
        val docId = params["doc_id"]?.trim()?.ifBlank { null }
            ?: (if (path.isNotBlank()) File(path).nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]+"), "_")
            else "doc_${UUID.randomUUID().toString().take(8)}")

        if (content.isBlank() && path.isNotBlank()) {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return ToolResult.Failed("File not found at path: $path")
            }
            try {
                content = file.readText(Charsets.UTF_8)
                if (title.isBlank()) {
                    title = file.name
                }
            } catch (e: Exception) {
                return ToolResult.Failed("Failed to read file '$path': ${e.message}")
            }
        }

        if (content.isBlank()) {
            return ToolResult.Failed("Either 'content' or valid 'path' must be provided to index a document")
        }

        if (title.isBlank()) {
            title = "Document $docId"
        }

        val source = if (path.isNotBlank()) path else "direct_input"
        val (metadata, chunks) = chunker.chunk(
            rawText = content,
            docId = docId,
            title = title,
            sourcePath = source
        )

        if (chunks.isEmpty()) {
            return ToolResult.Failed("Document contained no indexable text")
        }

        indexStore.indexDocument(metadata, chunks)
        val stats = indexStore.getStats()

        return ToolResult.Success(
            "Successfully indexed document \"$title\" ($docId). Created ${chunks.size} semantic chunks (${metadata.totalWords} words). Knowledge Vault now contains ${stats.totalDocuments} documents and ${stats.totalChunks} chunks."
        )
    }
}
