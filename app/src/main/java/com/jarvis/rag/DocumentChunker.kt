package com.jarvis.rag

import java.security.MessageDigest
import java.util.Locale

data class DocumentChunk(
    val chunkId: String,
    val docId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val title: String,
    val sourcePath: String,
    val content: String,
    val wordCount: Int,
    val checksum: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class DocumentMetadata(
    val docId: String,
    val title: String,
    val sourcePath: String,
    val checksum: String,
    val totalChunks: Int,
    val totalWords: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

class DocumentChunker(
    private val defaultChunkSizeWords: Int = 180,
    private val defaultOverlapWords: Int = 35
) {
    /**
     * Splits raw text into overlapping chunks, attempting to respect sentence
     * and paragraph boundaries to preserve semantic context.
     */
    fun chunk(
        rawText: String,
        docId: String,
        title: String = "Untitled",
        sourcePath: String = "inline",
        chunkSizeWords: Int = defaultChunkSizeWords,
        overlapWords: Int = defaultOverlapWords
    ): Pair<DocumentMetadata, List<DocumentChunk>> {
        val cleanText = rawText.trim()
        if (cleanText.isBlank()) {
            val emptyMeta = DocumentMetadata(
                docId = docId,
                title = title,
                sourcePath = sourcePath,
                checksum = computeHash(""),
                totalChunks = 0,
                totalWords = 0
            )
            return Pair(emptyMeta, emptyList())
        }

        val sentences = splitIntoSentences(cleanText)
        val chunkTexts = mutableListOf<String>()
        val currentWords = mutableListOf<String>()

        for (sentence in sentences) {
            val sentenceWords = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (sentenceWords.isEmpty()) continue

            if (currentWords.size + sentenceWords.size > chunkSizeWords && currentWords.isNotEmpty()) {
                val chunkContent = currentWords.joinToString(" ")
                chunkTexts.add(chunkContent)

                // Retain overlap from end of current chunk
                val overlap = currentWords.takeLast(overlapWords.coerceAtMost(currentWords.size))
                currentWords.clear()
                currentWords.addAll(overlap)
            }
            currentWords.addAll(sentenceWords)
        }

        if (currentWords.isNotEmpty()) {
            chunkTexts.add(currentWords.joinToString(" "))
        }

        val validChunks = chunkTexts.filter { it.isNotBlank() }
        val totalChunks = validChunks.size
        val docChecksum = computeHash(cleanText)
        val allWordsCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }

        val metadata = DocumentMetadata(
            docId = docId,
            title = title,
            sourcePath = sourcePath,
            checksum = docChecksum,
            totalChunks = totalChunks,
            totalWords = allWordsCount
        )

        val chunks = validChunks.mapIndexed { index, content ->
            val words = content.split(Regex("\\s+")).filter { it.isNotBlank() }
            DocumentChunk(
                chunkId = "${docId}_c$index",
                docId = docId,
                chunkIndex = index,
                totalChunks = totalChunks,
                title = title,
                sourcePath = sourcePath,
                content = content,
                wordCount = words.size,
                checksum = computeHash(content)
            )
        }

        return Pair(metadata, chunks)
    }

    private fun splitIntoSentences(text: String): List<String> {
        val paragraphs = text.split(Regex("\n{2,}"))
        val sentences = mutableListOf<String>()

        for (p in paragraphs) {
            val rawSentences = p.split(Regex("(?<=[.!?])\\s+"))
            for (s in rawSentences) {
                val t = s.trim()
                if (t.isNotBlank()) sentences.add(t)
            }
        }
        return sentences
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
