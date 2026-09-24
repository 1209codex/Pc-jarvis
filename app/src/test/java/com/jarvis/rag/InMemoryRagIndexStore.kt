package com.jarvis.rag

import java.util.Locale

class InMemoryRagIndexStore : IRagIndexStore {
    private val documents = mutableMapOf<String, DocumentMetadata>()
    private val chunks = mutableMapOf<String, DocumentChunk>()
    private val termPostings = mutableMapOf<String, MutableList<TermPosting>>()

    @Synchronized
    override fun indexDocument(metadata: DocumentMetadata, chunks: List<DocumentChunk>) {
        deleteDocument(metadata.docId)
        documents[metadata.docId] = metadata

        for (chunk in chunks) {
            this.chunks[chunk.chunkId] = chunk
            val tokens = RagIndexStore.tokenize("${chunk.title} ${chunk.content}")
            val tfMap = mutableMapOf<String, Int>()
            for (t in tokens) {
                tfMap[t] = (tfMap[t] ?: 0) + 1
            }
            for ((t, freq) in tfMap) {
                val list = termPostings.getOrPut(t) { mutableListOf() }
                list.add(TermPosting(chunk.chunkId, chunk.docId, freq))
            }
        }
    }

    @Synchronized
    override fun deleteDocument(docId: String) {
        documents.remove(docId)
        val removedChunkIds = chunks.values.filter { it.docId == docId }.map { it.chunkId }.toSet()
        for (cid in removedChunkIds) {
            chunks.remove(cid)
        }
        for ((_, list) in termPostings) {
            list.removeAll { it.docId == docId }
        }
    }

    override fun getChunk(chunkId: String): DocumentChunk? = chunks[chunkId]

    override fun getAllChunks(): List<DocumentChunk> = chunks.values.toList()

    override fun getChunksForDocument(docId: String): List<DocumentChunk> =
        chunks.values.filter { it.docId == docId }.sortedBy { it.chunkIndex }

    override fun getAllDocuments(): List<DocumentMetadata> =
        documents.values.sortedByDescending { it.createdAt }

    override fun getTermPostings(term: String): List<TermPosting> =
        termPostings[term.lowercase(Locale.US)] ?: emptyList()

    override fun getStats(): RagStats {
        val totalDocs = documents.size
        val totalChunks = chunks.size
        val totalWords = chunks.values.sumOf { it.wordCount }
        val avgLen = if (totalChunks > 0) totalWords.toDouble() / totalChunks else 0.0
        return RagStats(totalDocs, totalChunks, totalWords, avgLen)
    }
}