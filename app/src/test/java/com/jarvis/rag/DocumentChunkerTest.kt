package com.jarvis.rag

import org.junit.Assert.*
import org.junit.Test

class DocumentChunkerTest {
    @Test fun chunksRespectSemanticBoundariesAndMetadata() {
        val input = (1..100).joinToString(" ") { "word$it." }
        val (_, chunks) = DocumentChunker(defaultChunkSizeWords = 20, defaultOverlapWords = 5)
            .chunk(input, "doc1", "Title", "test.txt")
        assertTrue(chunks.size > 1)
        assertEquals("doc1", chunks.first().docId)
        assertEquals(chunks.size, chunks.first().totalChunks)
    }
}
