package com.jarvis.rag

class RagContextInjector(
    private val retriever: RagRetriever,
    private val maxTokensApprox: Int = 1200
) {
    /**
     * Injects retrieved document evidence into a structured RAG context block.
     */
    fun injectContext(query: String, limit: Int = 3): String {
        val result = retriever.retrieve(query, limit = limit)
        if (result.candidates.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("=== RAG KNOWLEDGE VAULT: RETRIEVED EVIDENCE ===\n")
        sb.append("IMPORTANT: Ground your response strictly in the following retrieved documents. Cite sources where appropriate.\n\n")

        var currentLength = sb.length
        for ((index, candidate) in result.candidates.withIndex()) {
            val chunk = candidate.chunk
            val chunkHeader = "[DOC #${index + 1}: ${chunk.title} (Source: ${chunk.sourcePath}, Chunk: ${chunk.chunkIndex + 1}/${chunk.totalChunks} | Match: ${"%.2f".format(candidate.fusedScore)})]\n"
            val chunkBody = "${chunk.content}\n\n"

            val approxTokens = (chunkHeader.length + chunkBody.length) / 4
            if (currentLength / 4 + approxTokens > maxTokensApprox && index > 0) {
                break
            }

            sb.append(chunkHeader)
            sb.append(chunkBody)
            currentLength += chunkHeader.length + chunkBody.length
        }

        sb.append("=== END RAG EVIDENCE ===\n")
        return sb.toString().trim()
    }

    /**
     * Format a direct user-facing summary of retrieved citations.
     */
    fun formatUserCitationSummary(query: String, limit: Int = 3): String {
        val result = retriever.retrieve(query, limit = limit)
        if (result.candidates.isEmpty()) {
            return "No relevant information found in Knowledge Vault for \"$query\"."
        }

        val sb = StringBuilder()
        sb.append("Found ${result.candidates.size} relevant sources in Knowledge Vault:\n\n")
        for ((index, candidate) in result.candidates.withIndex()) {
            val chunk = candidate.chunk
            val scorePercent = (candidate.fusedScore * 100).toInt()
            sb.append("${index + 1}. **${chunk.title}** (${chunk.sourcePath}) — *${scorePercent}% match*\n")
            sb.append("   > \"${candidate.highlightSnippet}\"\n\n")
        }
        return sb.toString().trim()
    }
}
