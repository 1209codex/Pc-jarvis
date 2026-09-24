package com.jarvis.rag

import com.jarvis.ai.EmbeddingVectorizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import java.util.LinkedHashMap

data class RagCandidate(
    val chunk: DocumentChunk,
    val bm25Score: Double,
    val vectorScore: Double,
    val fusedScore: Double,
    val highlightSnippet: String,
    val denseScore: Double = 0.0,
    val rerankScore: Double = 0.0,
    val confidence: Double = 0.0,
    val source: String = ""
)

fun interface RagReranker {
    fun score(query: String, candidate: DocumentChunk): Double
}

class LexicalCrossEncoderFallback : RagReranker {
    override fun score(query: String, candidate: DocumentChunk): Double {
        val q = RagIndexStore.tokenize(query).toSet()
        val text = RagIndexStore.tokenize("${candidate.title} ${candidate.content}")
        if (q.isEmpty() || text.isEmpty()) return 0.0
        val overlap = q.count { it in text }.toDouble() / q.size
        val phraseBonus = if (candidate.content.contains(query, ignoreCase = true)) 0.25 else 0.0
        return (overlap + phraseBonus).coerceIn(0.0, 1.0)
    }
}

data class RagQueryResult(
    val query: String,
    val candidates: List<RagCandidate>,
    val totalIndexedChunks: Int
)

class RagRetriever(
    private val indexStore: IRagIndexStore,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
    private val vectorizer: EmbeddingVectorizer? = null,
    private val reranker: RagReranker = LexicalCrossEncoderFallback()
) {
    // Chunk embeddings are deterministic per content: computed once per chunk
    // and reused across queries instead of re-embedding every chunk each call.
    private val denseCache = mutableMapOf<String, FloatArray>()
    private val queryCache = object : LinkedHashMap<String, RagQueryResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RagQueryResult>?): Boolean = size > 64
    }

    private fun chunkVector(chunk: DocumentChunk, vectorizer: EmbeddingVectorizer): FloatArray =
        denseCache.getOrPut("${chunk.chunkId}:${chunk.checksum}") {
            vectorizer.embed("${chunk.title} ${chunk.content}")
        }

    /**
     * Executes hybrid retrieval (BM25 + TF-IDF Vector Cosine [+ local dense
     * embeddings]) using Reciprocal Rank Fusion. When no vectorizer is given,
     * retrieval fails closed to key word + term-vector only.
     */
    @Synchronized
    fun retrieve(query: String, limit: Int = 4, docIdFilter: String? = null): RagQueryResult {
        val cacheKey = "${query.trim().lowercase(Locale.ROOT)}|$limit|${docIdFilter.orEmpty()}"
        queryCache[cacheKey]?.let { return it }
        val queryTokens = RagIndexStore.tokenize(query)
        val stats = indexStore.getStats()
        if (queryTokens.isEmpty() || stats.totalChunks == 0) {
            return RagQueryResult(query, emptyList(), stats.totalChunks)
        }

        val allChunks = if (docIdFilter != null) {
            indexStore.getChunksForDocument(docIdFilter)
        } else {
            indexStore.getAllChunks()
        }
        if (allChunks.isEmpty()) {
            return RagQueryResult(query, emptyList(), stats.totalChunks)
        }

        val chunkMap = allChunks.associateBy { it.chunkId }
        val nChunks = stats.totalChunks.toDouble()
        val avgDocLen = if (stats.avgChunkLength > 0.0) stats.avgChunkLength else 100.0

        // 1. Calculate BM25 scores for each candidate chunk
        val bm25Scores = mutableMapOf<String, Double>()
        val termPostingsMap = mutableMapOf<String, List<TermPosting>>()
        val idfMap = mutableMapOf<String, Double>()

        for (term in queryTokens.distinct()) {
            val postings = indexStore.getTermPostings(term).filter {
                if (docIdFilter != null) it.docId == docIdFilter else true
            }
            termPostingsMap[term] = postings
            val n_q = postings.size.toDouble()
            // Okapi BM25 Robertson-Sparck Jones IDF
            val idf = ln(1.0 + (nChunks - n_q + 0.5) / (n_q + 0.5)).coerceAtLeast(0.1)
            idfMap[term] = idf

            for (posting in postings) {
                val chunk = chunkMap[posting.chunkId] ?: continue
                val docLen = chunk.wordCount.coerceAtLeast(1)
                val tf = posting.freq.toDouble()
                val numerator = tf * (k1 + 1.0)
                val denominator = tf + k1 * (1.0 - b + b * (docLen / avgDocLen))
                val termScore = idf * (numerator / denominator)

                bm25Scores[posting.chunkId] = (bm25Scores[posting.chunkId] ?: 0.0) + termScore
            }
        }

        // 2. Calculate TF-IDF Vector Cosine Similarity
        val queryTermFreq = mutableMapOf<String, Int>()
        for (token in queryTokens) {
            queryTermFreq[token] = (queryTermFreq[token] ?: 0) + 1
        }

        var queryNormSq = 0.0
        val queryWeights = mutableMapOf<String, Double>()
        for ((term, qf) in queryTermFreq) {
            val idf = idfMap[term] ?: 0.1
            val w = (1.0 + ln(qf.toDouble())) * idf
            queryWeights[term] = w
            queryNormSq += w * w
        }
        val queryNorm = sqrt(queryNormSq).coerceAtLeast(1e-6)

        val vectorScores = mutableMapOf<String, Double>()
        val denseScores = mutableMapOf<String, Double>()
        val vectorizerRef = vectorizer
        val queryEmbedding = vectorizerRef?.embed(query)
        for (chunk in allChunks) {
            val chunkTokens = RagIndexStore.tokenize("${chunk.title} ${chunk.content}")
            if (chunkTokens.isEmpty()) continue

            val chunkTermFreq = mutableMapOf<String, Int>()
            for (t in chunkTokens) {
                chunkTermFreq[t] = (chunkTermFreq[t] ?: 0) + 1
            }

            var dotProduct = 0.0
            var chunkNormSq = 0.0

            for ((t, cf) in chunkTermFreq) {
                val idf = idfMap[t] ?: 0.1
                val w = (1.0 + ln(cf.toDouble())) * idf
                chunkNormSq += w * w
                val qw = queryWeights[t]
                if (qw != null) {
                    dotProduct += qw * w
                }
            }

            val chunkNorm = sqrt(chunkNormSq).coerceAtLeast(1e-6)
            val cosineSim = (dotProduct / (queryNorm * chunkNorm)).coerceIn(0.0, 1.0)
            if (cosineSim > 0.01) {
                vectorScores[chunk.chunkId] = cosineSim
            }

            if (vectorizerRef != null && queryEmbedding != null) {
                val sim = vectorizerRef.cosine(queryEmbedding, chunkVector(chunk, vectorizerRef))
                if (sim > 0.01) {
                    denseScores[chunk.chunkId] = sim
                }
            }
        }

        // Rank candidate IDs by BM25
        val sortedBm25 = bm25Scores.entries.sortedByDescending { it.value }.map { it.key }
        val bm25Ranks = sortedBm25.mapIndexed { idx, cid -> cid to (idx + 1) }.toMap()

        // Rank candidate IDs by Vector
        val sortedVector = vectorScores.entries.sortedByDescending { it.value }.map { it.key }
        val vectorRanks = sortedVector.mapIndexed { idx, cid -> cid to (idx + 1) }.toMap()

        // Rank candidate IDs by Dense embeddings
        val sortedDense = denseScores.entries.sortedByDescending { it.value }.map { it.key }
        val denseRanks = sortedDense.mapIndexed { idx, cid -> cid to (idx + 1) }.toMap()

        val candidateIds = (sortedBm25 + sortedVector + sortedDense).distinct()
        if (candidateIds.isEmpty()) {
            return RagQueryResult(query, emptyList(), stats.totalChunks)
        }

        // 3. Reciprocal Rank Fusion (RRF)
        val rrfConstant = 60.0
        val candidates = mutableListOf<RagCandidate>()

        val hasDense = denseScores.isNotEmpty()
        val wBm25 = if (hasDense) 0.40 else 0.5
        val wVec = if (hasDense) 0.30 else 0.5
        val wDense = if (hasDense) 0.30 else 0.0

        for (cid in candidateIds) {
            val chunk = chunkMap[cid] ?: continue
            val rBm25 = bm25Ranks[cid] ?: 1000
            val rVec = vectorRanks[cid] ?: 1000
            val rDense = denseRanks[cid] ?: 1000

            val rrfScore =
                (wBm25 / (rrfConstant + rBm25)) +
                    (wVec / (rrfConstant + rVec)) +
                    (wDense / (rrfConstant + rDense))
            val bm25Score = bm25Scores[cid] ?: 0.0
            val vecScore = vectorScores[cid] ?: 0.0
            val denseScore = denseScores[cid] ?: 0.0

            val highlight = extractSnippet(chunk.content, queryTokens)

            candidates.add(
                RagCandidate(
                    chunk = chunk,
                    bm25Score = bm25Score,
                    vectorScore = vecScore,
                    fusedScore = rrfScore,
                    highlightSnippet = highlight,
                    denseScore = denseScore
                )
            )
        }

        // Retrieve a wider pool, then rerank the most promising candidates.
        // A production cross-encoder can implement RagReranker without changing
        // the retrieval contract; the fallback is deterministic and offline-safe.
        val rerankPool = candidates.sortedByDescending { it.fusedScore }.take((limit * 4).coerceAtLeast(8))
        val reranked = rerankPool.map { c ->
            c.copy(rerankScore = reranker.score(query, c.chunk))
        }.sortedByDescending { 0.65 * it.rerankScore + 0.35 * it.fusedScore }.take(limit)

        // Calibrate final score and expose source attribution/confidence.
        val maxScore = reranked.firstOrNull()?.let { 0.65 * it.rerankScore + 0.35 * it.fusedScore } ?: 1.0
        val calibrated = reranked.map { c ->
            val finalScore = 0.65 * c.rerankScore + 0.35 * c.fusedScore
            val normalized = if (maxScore > 0.0) (finalScore / maxScore).coerceIn(0.0, 1.0) else 0.0
            c.copy(
                fusedScore = normalized,
                confidence = normalized,
                source = c.chunk.sourcePath
            )
        }

        val result = RagQueryResult(query, calibrated, stats.totalChunks)
        queryCache[cacheKey] = result
        return result
    }

    @Synchronized
    fun clearCache() {
        queryCache.clear()
        denseCache.clear()
    }

    private fun extractSnippet(content: String, queryTokens: List<String>): String {
        val sentences = content.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return content.take(160)

        // Find sentence with highest token overlap
        var bestSentence = sentences.first()
        var maxMatches = -1

        for (s in sentences) {
            val sTokens = RagIndexStore.tokenize(s)
            val matches = queryTokens.count { sTokens.contains(it) }
            if (matches > maxMatches) {
                maxMatches = matches
                bestSentence = s
            }
        }

        return bestSentence.trim()
    }
}
