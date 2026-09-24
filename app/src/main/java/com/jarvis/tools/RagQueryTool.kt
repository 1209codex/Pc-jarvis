package com.jarvis.tools

import com.jarvis.execution.VerificationResult
import com.jarvis.rag.RagRetriever

class RagQueryTool(
    private val retriever: RagRetriever
) : Tool {
    override val name: String = "RAG_RETRIEVE"
    override val description: String =
        "Search indexed documents, personal notes, and knowledge base files using hybrid BM25 and vector semantic retrieval. Params: query (required), limit (optional), doc_id (optional)."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim()
            ?: params["q"]?.trim()
            ?: params["text"]?.trim()
            ?: return ToolResult.Failed("Parameter 'query' is required for RAG_RETRIEVE")

        if (query.isBlank()) {
            return ToolResult.Failed("Query cannot be blank")
        }

        val limit = params["limit"]?.toIntOrNull() ?: 4
        val docId = params["doc_id"]?.trim()?.ifBlank { null }

        val result = retriever.retrieve(query = query, limit = limit, docIdFilter = docId)
        if (result.candidates.isEmpty()) {
            return ToolResult.Success("No matching document chunks found in Knowledge Vault for: \"$query\" (total chunks indexed: ${result.totalIndexedChunks})")
        }

        val sb = StringBuilder()
        sb.append("Found ${result.candidates.size} relevant document sources:\n\n")
        for ((index, candidate) in result.candidates.withIndex()) {
            val chunk = candidate.chunk
            val matchPct = (candidate.fusedScore * 100).toInt()
            sb.append("[${index + 1}] \"${chunk.title}\" (${chunk.sourcePath} | chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks})\n")
            sb.append("Match Score: $matchPct% (BM25: ${"%.2f".format(candidate.bm25Score)}, Vec: ${"%.2f".format(candidate.vectorScore)})\n")
            sb.append("Snippet: \"${candidate.highlightSnippet}\"\n\n")
        }

        return ToolResult.Success(sb.toString().trim())
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("RAG retrieval failed: ${result.message}")
        }
        val text = result.message
        val found = Regex("Found (\\d+) relevant").find(text)
        return when {
            found != null -> VerificationResult.success(
                "RAG retrieval returned ${found.groupValues[1]} source(s) for '${params["query"]}'",
                mapOf("count" to found.groupValues[1], "outcome" to "RESULTS_FOUND")
            )
            text.contains("No matching document chunks", ignoreCase = true) -> VerificationResult.success(
                "Knowledge Vault searched; no matches for '${params["query"]}'",
                mapOf("outcome" to "NO_MATCHES")
            )
            else -> VerificationResult.unknown("RAG retrieval returned an unrecognized response", mapOf("outcome" to "UNRECOGNIZED"))
        }
    }
}
