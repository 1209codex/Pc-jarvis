package com.jarvis.retrieval.engine

import com.jarvis.autonomous.AmbientContextEngine
import com.jarvis.memory.MemoryStore
import com.jarvis.rag.RagRetriever
import com.jarvis.retrieval.context.StructuredContextBuilder
import com.jarvis.retrieval.model.CandidateMetadata
import com.jarvis.retrieval.model.CandidateSource
import com.jarvis.retrieval.model.EvaluatedCandidate
import com.jarvis.retrieval.model.RetrievalEvent
import com.jarvis.retrieval.reranker.RerankerRouter
import com.jarvis.retrieval.router.QueryClassifier
import com.jarvis.retrieval.router.RetrievalRoute
import com.jarvis.retrieval.router.RetrievalRouter
import java.util.UUID

class RaphaelRetrievalManager(
    private val router: RetrievalRouter = RetrievalRouter(QueryClassifier()),
    private val ragRetriever: RagRetriever? = null,
    private val memoryStore: MemoryStore? = null,
    private val ambientEngine: AmbientContextEngine? = null,
    private val fusionEngine: CandidateFusionEngine = CandidateFusionEngine(),
    private val rerankerRouter: RerankerRouter = RerankerRouter(),
    private val contextBuilder: StructuredContextBuilder = StructuredContextBuilder()
) {
    private val maxEventLogs = 100
    private val eventLogs = java.util.Collections.synchronizedList(mutableListOf<RetrievalEvent>())

    private fun logEvent(event: RetrievalEvent) {
        synchronized(eventLogs) {
            eventLogs.add(event)
            while (eventLogs.size > maxEventLogs) {
                eventLogs.removeAt(0)
            }
        }
    }

    /**
     * Shared candidate gathering for steps 1-4 (RAG, MAG, CAG, Lexical).
     * Extracted to eliminate duplication between retrieve() and retrieveDetailed().
     */
    private data class GatheredCandidates(
        val rag: List<CandidateMetadata>,
        val mag: List<CandidateMetadata>,
        val cag: List<CandidateMetadata>,
        val lexical: List<CandidateMetadata>
    )

    private fun gatherCandidates(
        query: String,
        stateContext: String,
        route: RetrievalRoute,
        topK: Int
    ): GatheredCandidates {
        val ragCandidates = mutableListOf<CandidateMetadata>()
        val magCandidates = mutableListOf<CandidateMetadata>()
        val cagCandidates = mutableListOf<CandidateMetadata>()
        val lexicalCandidates = mutableListOf<CandidateMetadata>()

        // 1. RAG Retrieval
        if (route in listOf(RetrievalRoute.RAG, RetrievalRoute.FAST_RAG, RetrievalRoute.HYBRID)) {
            ragRetriever?.let { r ->
                val result = r.retrieve(query, limit = topK)
                result.candidates.forEach { c ->
                    ragCandidates.add(
                        CandidateMetadata(
                            id = c.chunk.chunkId,
                            source = CandidateSource.KNOWLEDGE_RAG,
                            text = "${c.chunk.title}: ${c.chunk.content}",
                            denseScore = c.denseScore,
                            lexicalScore = c.bm25Score,
                            importanceScore = 0.6,
                            sourcePriority = 0.7
                        )
                    )
                }
            }
        }

        // 2. MAG (Memory) Retrieval
        if (route in listOf(RetrievalRoute.MAG, RetrievalRoute.HYBRID)) {
            memoryStore?.let { m ->
                val items = m.getPreferences(limit = 6)
                items.forEach { item ->
                    magCandidates.add(
                        CandidateMetadata(
                            id = "mem_${item.id}",
                            source = CandidateSource.USER_MEMORY,
                            text = "${item.key}: ${item.content}",
                            denseScore = item.score,
                            importanceScore = item.importance,
                            sourcePriority = 0.9,
                            memoryId = item.id.toString(),
                            timestamp = item.createdAt,
                            isConfirmed = item.isConfirmed,
                            supersedesId = item.supersedesId
                        )
                    )
                }
            }
        }

        // 3. CAG (Current Context) Retrieval
        if (route in listOf(RetrievalRoute.CAG, RetrievalRoute.HYBRID)) {
            if (ambientEngine != null) {
                val snapshot = ambientEngine.inferCurrentContext()
                cagCandidates.add(
                    CandidateMetadata(
                        id = "cag_ambient",
                        source = CandidateSource.DEVICE_STATE_CAG,
                        text = "Ambient State: ${snapshot.state.displayName}, Battery: ${snapshot.batteryPercent}%, Charging: ${snapshot.isCharging}",
                        denseScore = 1.0,
                        importanceScore = 0.9,
                        sourcePriority = 0.95,
                        timestamp = snapshot.timestamp
                    )
                )
            } else {
                val currentText = if (stateContext.isNotBlank()) stateContext else "Device Context: Active Query session"
                cagCandidates.add(
                    CandidateMetadata(
                        id = "cag_default",
                        source = CandidateSource.DEVICE_STATE_CAG,
                        text = currentText,
                        denseScore = 1.0,
                        importanceScore = 0.9,
                        sourcePriority = 0.95
                    )
                )
            }
        }

        // 4. Lexical Retrieval from SQLite FTS
        if (route in listOf(RetrievalRoute.HYBRID, RetrievalRoute.FAST_RAG, RetrievalRoute.MAG, RetrievalRoute.RAG)) {
            memoryStore?.let { m ->
                val ftsItems = m.queryRelevantMemories(
                    query = query,
                    types = setOf(com.jarvis.memory.MemoryType.USER_PREFERENCE, com.jarvis.memory.MemoryType.STRUCTURED_FACT, com.jarvis.memory.MemoryType.SHORT_TERM),
                    limit = topK
                )
                ftsItems.forEach { item ->
                    val lexScore = calculateLexicalMatchScore(query, "${item.key} ${item.content}")
                    if (lexScore > 0.05) {
                        lexicalCandidates.add(
                            CandidateMetadata(
                                id = "lex_mem_${item.id}",
                                source = CandidateSource.LEXICAL_EXACT,
                                text = "${item.key}: ${item.content}",
                                denseScore = 0.0,
                                lexicalScore = lexScore,
                                importanceScore = item.importance,
                                sourcePriority = 0.85,
                                memoryId = item.id.toString(),
                                timestamp = item.createdAt,
                                isConfirmed = item.isConfirmed,
                                supersedesId = item.supersedesId
                            )
                        )
                    }
                }
            }
        }

        return GatheredCandidates(ragCandidates, magCandidates, cagCandidates, lexicalCandidates)
    }

    fun retrieve(
        query: String,
        stateContext: String = "",
        activeTask: String = "",
        topK: Int = 10
    ): String {
        val startTime = System.currentTimeMillis()
        val route = router.route(query, stateContext)

        if (route == RetrievalRoute.NO_RETRIEVAL) {
            return ""
        }

        val gathered = gatherCandidates(query, stateContext, route, topK)

        // 5. Candidate Fusion
        val fused = fusionEngine.fuse(
            ragCandidates = gathered.rag,
            magCandidates = gathered.mag,
            cagCandidates = gathered.cag,
            lexicalCandidates = gathered.lexical
        )

        // 6. Re-Ranking check & evaluation
        val evaluated: List<EvaluatedCandidate>
        val rerankerUsed: Boolean

        if (route == RetrievalRoute.HYBRID || rerankerRouter.shouldRerank(query, fused)) {
            evaluated = rerankerRouter.rankCandidates(query, fused)
            rerankerUsed = true
        } else {
            evaluated = fused.map { EvaluatedCandidate(candidate = it, compositeScore = it.denseScore) }
                .sortedByDescending { it.compositeScore }
            rerankerUsed = false
        }

        val latency = System.currentTimeMillis() - startTime
        val event = RetrievalEvent(
            id = UUID.randomUUID().toString(),
            query = query,
            route = route.name,
            candidateCount = fused.size,
            rerankerUsed = rerankerUsed,
            finalCount = evaluated.take(topK).size,
            latencyMs = latency,
            confidenceScore = evaluated.firstOrNull()?.compositeScore ?: 0.0
        )
        logEvent(event)

        // 7. Build structured context
        return contextBuilder.build(
            query = query,
            candidates = evaluated.take(topK),
            systemState = stateContext,
            activeTask = activeTask
        )
    }

    fun retrieveDetailed(
        query: String,
        stateContext: String = "",
        activeTask: String = "",
        topK: Int = 10
    ): com.jarvis.retrieval.model.DetailedRetrievalResult {
        val startTime = System.currentTimeMillis()
        val intent = router.classifier.classify(query)
        val route = router.route(query, stateContext)

        if (route == RetrievalRoute.NO_RETRIEVAL) {
            return com.jarvis.retrieval.model.DetailedRetrievalResult(
                query = query,
                intent = intent,
                route = route,
                candidates = emptyList(),
                rerankerUsed = false,
                latencyMs = System.currentTimeMillis() - startTime,
                structuredContext = ""
            )
        }

        val gathered = gatherCandidates(query, stateContext, route, topK)

        // 5. Fusion
        val fused = fusionEngine.fuse(
            ragCandidates = gathered.rag,
            magCandidates = gathered.mag,
            cagCandidates = gathered.cag,
            lexicalCandidates = gathered.lexical
        )

        // 6. Reranking
        val evaluated: List<EvaluatedCandidate>
        val rerankerUsed: Boolean

        if (route == RetrievalRoute.HYBRID || rerankerRouter.shouldRerank(query, fused)) {
            evaluated = rerankerRouter.rankCandidates(query, fused)
            rerankerUsed = true
        } else {
            evaluated = fused.map { EvaluatedCandidate(candidate = it, compositeScore = it.denseScore) }
                .sortedByDescending { it.compositeScore }
            rerankerUsed = false
        }

        val latency = System.currentTimeMillis() - startTime
        val contextText = contextBuilder.build(
            query = query,
            candidates = evaluated.take(topK),
            systemState = stateContext,
            activeTask = activeTask
        )

        val activeRerankerName = if (rerankerUsed) rerankerRouter.getActiveRerankerName() else "None"

        return com.jarvis.retrieval.model.DetailedRetrievalResult(
            query = query,
            intent = intent,
            route = route,
            candidates = evaluated.take(topK),
            rerankerUsed = rerankerUsed,
            latencyMs = latency,
            structuredContext = contextText,
            rerankerName = activeRerankerName
        )
    }

    private fun calculateLexicalMatchScore(query: String, text: String): Double {
        val qTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
        if (qTokens.isEmpty()) return 0.0
        val tLower = text.lowercase()
        val tTokens = tLower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
        if (tTokens.isEmpty()) return 0.0

        val overlap = qTokens.intersect(tTokens).size
        val jaccard = overlap.toDouble() / (qTokens.union(tTokens).size)
        val phraseBonus = if (tLower.contains(query.trim().lowercase())) 0.4 else 0.0
        return (jaccard * 0.6 + phraseBonus).coerceIn(0.0, 1.0)
    }

    fun getEventLogs(): List<RetrievalEvent> = synchronized(eventLogs) { eventLogs.toList() }
}
