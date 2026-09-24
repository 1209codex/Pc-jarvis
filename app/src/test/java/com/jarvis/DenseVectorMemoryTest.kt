package com.jarvis

import com.jarvis.ai.EmbeddingVectorizer
import com.jarvis.foundation.SensitiveTerms
import com.jarvis.rag.DocumentChunker
import com.jarvis.rag.InMemoryRagIndexStore
import com.jarvis.rag.RagRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseVectorMemoryTest {

    @Test
    fun embeddingsAreDeterministic() {
        val v = EmbeddingVectorizer()
        val a = v.embed("exercise every morning at sunrise")
        val b = v.embed("exercise every morning at sunrise")
        assertEquals(1.0, v.cosine(a, b), 0.0001)
    }

    @Test
    fun morphologicalVariantsScoreAboveUnrelatedText() {
        val v = EmbeddingVectorizer()
        val running = v.embed("running fast improves endurance")
        val related = v.embed("runs improve cardio")
        val unrelated = v.embed("apples bananas mangoes oranges")
        assertTrue(v.cosine(running, related) > v.cosine(running, unrelated))
    }

    @Test
    fun emptyOrShortTextYieldsZeroSimilarity() {
        val v = EmbeddingVectorizer()
        val empty = v.embed("a b")   // tokens shorter than 2 chars produce no features
        val text = v.embed("exercise routine")
        assertEquals(0.0, v.cosine(empty, text), 0.0001)
    }

    @Test
    fun denseRankingSurfacesFitnessChunkForMorphologicalVariantQuery() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker(defaultChunkSizeWords = 40, defaultOverlapWords = 5)

        val fitness =
            "Exercise improves cardiovascular fitness and muscular endurance. Regular workouts raise heart rate and build strength."
        val nutrition =
            "Eating vegetables and lean protein supports healthy digestion and provides essential vitamins to the body."
        val (metaF, chunksF) = chunker.chunk(fitness, "doc_fitness", "Fitness Guide", "/docs/fitness.txt")
        val (metaN, chunksN) = chunker.chunk(nutrition, "doc_nutrition", "Nutrition Guide", "/docs/nutrition.txt")
        store.indexDocument(metaF, chunksF)
        store.indexDocument(metaN, chunksN)

        val retriever = RagRetriever(store, vectorizer = EmbeddingVectorizer())

        // "exercises" is a morphological variant of "exercise": pure token BM25/TF-IDF
        // miss it, the local dense n-gram embedding catches the shared stem.
        val result = retriever.retrieve("exercises for heart strength", limit = 1)

        assertFalse(result.candidates.isEmpty())
        assertEquals("doc_fitness", result.candidates[0].chunk.docId)
        assertTrue(result.candidates[0].denseScore > 0.0)

        // Second call on the same retriever reuses the cached chunk embeddings and
        // must still surface the semantically related chunk.
        val again = retriever.retrieve("workouts heart endurance", limit = 1)
        assertFalse(again.candidates.isEmpty())
        assertEquals("doc_fitness", again.candidates[0].chunk.docId)
        assertTrue(again.candidates[0].denseScore > 0.0)
    }

    @Test
    fun withoutVectorizerRetrievalFailsClosedToKeyword() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker(defaultChunkSizeWords = 40, defaultOverlapWords = 5)
        val (meta1, chunks1) = chunker.chunk(
            "Exercise improves cardiovascular fitness and muscular endurance.",
            "doc_fitness", "Fitness Guide", "/docs/fitness.txt"
        )
        store.indexDocument(meta1, chunks1)

        val retriever = RagRetriever(store)
        val result = retriever.retrieve("exercise", limit = 1)
        assertFalse(result.candidates.isEmpty())
        assertEquals(0.0, result.candidates[0].denseScore, 0.0001)
    }

    @Test
    fun sensitiveTermsDetectCredentials() {
        assertTrue(SensitiveTerms.contains("what is my otp"))
        assertTrue(SensitiveTerms.contains("credit card pin number"))
        assertFalse(SensitiveTerms.contains("play some music"))
    }
}