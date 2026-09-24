package com.jarvis

import com.jarvis.agent.AgentContextRouter
import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.ContextNeed
import com.jarvis.agent.skills.RagSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.rag.*
import com.jarvis.tools.RagIndexTool
import com.jarvis.tools.RagQueryTool
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RagPipelineTest {

    @Test
    fun testDocumentChunkerSentenceBoundariesAndOverlap() {
        val chunker = DocumentChunker(defaultChunkSizeWords = 20, defaultOverlapWords = 5)
        val text = """
            Android is an operating system for mobile devices. It is developed by Google.
            Kotlin is the preferred programming language for Android development. It provides modern syntax and null safety.
            Jetpack Compose is Android modern toolkit for building native UI. It simplifies and accelerates UI development on Android.
        """.trimIndent()

        val (metadata, chunks) = chunker.chunk(
            rawText = text,
            docId = "doc_android_overview",
            title = "Android Overview",
            sourcePath = "/sdcard/Documents/android.txt"
        )

        assertEquals("doc_android_overview", metadata.docId)
        assertEquals("Android Overview", metadata.title)
        assertTrue("Total words should be > 30", metadata.totalWords > 30)
        assertTrue("Should produce at least 2 chunks", chunks.size >= 2)

        val firstChunk = chunks[0]
        assertEquals(0, firstChunk.chunkIndex)
        assertEquals(chunks.size, firstChunk.totalChunks)
        assertTrue("Chunk content should contain sentence parts", firstChunk.content.contains("operating system"))
        assertNotNull(firstChunk.checksum)
    }

    @Test
    fun testInMemoryRagIndexStoreIndexingAndStats() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker()

        val text1 = "Quantum computing harnesses the phenomena of quantum mechanics such as superposition and entanglement to deliver massive parallelism."
        val (meta1, chunks1) = chunker.chunk(text1, "doc_quantum", "Quantum Computing", "/docs/quantum.txt")
        store.indexDocument(meta1, chunks1)

        val text2 = "Artificial Intelligence and deep learning utilize multilayer neural networks trained with stochastic gradient descent."
        val (meta2, chunks2) = chunker.chunk(text2, "doc_ai", "AI & Deep Learning", "/docs/ai.txt")
        store.indexDocument(meta2, chunks2)

        val stats = store.getStats()
        assertEquals(2, stats.totalDocuments)
        assertTrue(stats.totalChunks >= 2)
        assertTrue(stats.totalWords > 20)

        val quantumPostings = store.getTermPostings("quantum")
        assertTrue(quantumPostings.isNotEmpty())
        assertEquals("doc_quantum", quantumPostings[0].docId)

        // Delete document test
        store.deleteDocument("doc_quantum")
        val statsAfterDelete = store.getStats()
        assertEquals(1, statsAfterDelete.totalDocuments)
        assertTrue(store.getTermPostings("quantum").isEmpty())
    }

    @Test
    fun testRagRetrieverBM25AndCosineSimilarity() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker()

        val doc1 = "Quantum computing relies on quantum bits or qubits. Superposition enables qubits to evaluate vast numbers of states simultaneously."
        val (meta1, chunks1) = chunker.chunk(doc1, "doc_quantum", "Quantum Computing", "/docs/quantum.txt")
        store.indexDocument(meta1, chunks1)

        val doc2 = "Jetpack Compose is Android modern declarative UI toolkit. It replaces traditional XML layouts with idiomatic Kotlin functions."
        val (meta2, chunks2) = chunker.chunk(doc2, "doc_compose", "Jetpack Compose", "/docs/compose.txt")
        store.indexDocument(meta2, chunks2)

        val doc3 = "Nutritional guidelines recommend drinking at least two liters of water daily alongside balanced protein and fiber intake."
        val (meta3, chunks3) = chunker.chunk(doc3, "doc_health", "Daily Nutrition", "/docs/health.txt")
        store.indexDocument(meta3, chunks3)

        val retriever = RagRetriever(store)

        // Query 1: Quantum qubits
        val result1 = retriever.retrieve("quantum qubits superposition", limit = 2)
        assertFalse(result1.candidates.isEmpty())
        assertEquals("doc_quantum", result1.candidates[0].chunk.docId)
        assertTrue(result1.candidates[0].fusedScore > 0.5)

        // Query 2: Android UI Kotlin
        val result2 = retriever.retrieve("Android declarative UI Kotlin", limit = 2)
        assertFalse(result2.candidates.isEmpty())
        assertEquals("doc_compose", result2.candidates[0].chunk.docId)

        // Query 3: Water nutrition
        val result3 = retriever.retrieve("drinking water hydration protein", limit = 2)
        assertFalse(result3.candidates.isEmpty())
        assertEquals("doc_health", result3.candidates[0].chunk.docId)
    }

    @Test
    fun testRagRetrieverDocumentIdFilter() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker()

        val doc1 = "Encryption standards like AES-256 secure sensitive local files."
        val (meta1, chunks1) = chunker.chunk(doc1, "doc_security", "Security Spec", "/docs/sec.txt")
        store.indexDocument(meta1, chunks1)

        val doc2 = "Encryption protocols protect user network communication and HTTPS connections."
        val (meta2, chunks2) = chunker.chunk(doc2, "doc_network", "Network Spec", "/docs/net.txt")
        store.indexDocument(meta2, chunks2)

        val retriever = RagRetriever(store)
        val filteredResult = retriever.retrieve("encryption", limit = 5, docIdFilter = "doc_security")
        assertEquals(1, filteredResult.candidates.size)
        assertEquals("doc_security", filteredResult.candidates[0].chunk.docId)
    }

    @Test
    fun testRagContextInjectorFormattingAndBudget() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker()

        val text = "Server configuration requires port 8080 open for websocket communication with client agents."
        val (meta, chunks) = chunker.chunk(text, "doc_server", "Server Configuration", "/docs/server.txt")
        store.indexDocument(meta, chunks)

        val retriever = RagRetriever(store)
        val injector = RagContextInjector(retriever)

        val promptContext = injector.injectContext("websocket port configuration")
        assertTrue(promptContext.contains("=== RAG KNOWLEDGE VAULT: RETRIEVED EVIDENCE ==="))
        assertTrue(promptContext.contains("Server Configuration"))
        assertTrue(promptContext.contains("port 8080"))

        val userSummary = injector.formatUserCitationSummary("websocket port")
        assertTrue(userSummary.contains("Server Configuration"))
        assertTrue(userSummary.contains("match"))
    }

    @Test
    fun testRagToolsExecution() = runBlocking {
        val store = InMemoryRagIndexStore()
        val indexTool = RagIndexTool(store)
        val retriever = RagRetriever(store)
        val queryTool = RagQueryTool(retriever)

        // Index document via tool
        val indexParams = mapOf(
            "content" to "The secret vault passcode is Delta-774-Echo for the laboratory safe.",
            "title" to "Lab Security Notes",
            "doc_id" to "lab_notes"
        )
        val indexResult = indexTool.execute(indexParams)
        assertTrue(indexResult is ToolResult.Success)
        assertTrue((indexResult as ToolResult.Success).message.contains("Successfully indexed document"))

        // Query document via tool
        val queryParams = mapOf("query" to "secret laboratory passcode")
        val queryResult = queryTool.execute(queryParams)
        assertTrue(queryResult is ToolResult.Success)
        val msg = (queryResult as ToolResult.Success).message
        assertTrue(msg.contains("Lab Security Notes"))
        assertTrue(msg.contains("Delta-774-Echo"))
    }

    @Test
    fun testRagSkillHandling() = runBlocking {
        val skill = RagSkill()
        val dummyContext = SkillContext("test", AgentWorkingMemory("test"))

        // 1. Can handle checks
        assertTrue(skill.canHandle("search documents for tax invoice", dummyContext))
        assertTrue(skill.canHandle("find in notes meeting minutes", dummyContext))
        assertTrue(skill.canHandle("index document /sdcard/readme.txt", dummyContext))
        assertFalse(skill.canHandle("play a song", dummyContext))

        // 2. Execution - retrieval
        val retrieveResult = skill.execute("search documents for quarterly budget", dummyContext)
        assertTrue(retrieveResult.handled)
        assertNotNull(retrieveResult.proposedAction)
        assertEquals("RAG_RETRIEVE", retrieveResult.proposedAction?.type)
        assertEquals("quarterly budget", retrieveResult.proposedAction?.params?.get("query"))

        // 3. Execution - indexing
        val indexResult = skill.execute("index note meeting summary on Monday", dummyContext)
        assertTrue(indexResult.handled)
        assertEquals("RAG_INDEX", indexResult.proposedAction?.type)
    }

    @Test
    fun testToolRegistryRagAliases() {
        val registry = ToolRegistry()
        val stubRetrieve = object : Tool {
            override val name: String = "RAG_RETRIEVE"
            override val description: String = ""
            override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("ok")
        }
        val stubIndex = object : Tool {
            override val name: String = "RAG_INDEX"
            override val description: String = ""
            override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("ok")
        }
        registry.register(stubRetrieve)
        registry.register(stubIndex)

        assertEquals("RAG_RETRIEVE", registry.resolveCanonicalToolName("SEARCH_DOCS"))
        assertEquals("RAG_RETRIEVE", registry.resolveCanonicalToolName("QUERY_KNOWLEDGE"))
        assertEquals("RAG_RETRIEVE", registry.resolveCanonicalToolName("KNOWLEDGE_SEARCH"))
        assertEquals("RAG_RETRIEVE", registry.resolveCanonicalToolName("SEARCH_NOTES"))
        assertEquals("RAG_RETRIEVE", registry.resolveCanonicalToolName("FIND_IN_FILES"))
        assertEquals("RAG_INDEX", registry.resolveCanonicalToolName("INDEX_DOCUMENT"))
        assertEquals("RAG_INDEX", registry.resolveCanonicalToolName("INDEX_FILE"))
        assertEquals("RAG_INDEX", registry.resolveCanonicalToolName("INDEX_NOTES"))
        assertEquals("RAG_INDEX", registry.resolveCanonicalToolName("ADD_TO_VAULT"))
    }

    @Test
    fun testIntentResolverRagIntents() {
        // Query Intent
        val res1 = IntentResolver.resolve("search documents for wifi credentials")
        assertNotNull(res1)
        assertEquals(AssistantIntent.RAG_QUERY, res1?.intent)
        assertEquals("wifi credentials", res1?.params?.get("query"))

        val res2 = IntentResolver.resolve("find in notes project deadlines")
        assertNotNull(res2)
        assertEquals(AssistantIntent.RAG_QUERY, res2?.intent)
        assertEquals("project deadlines", res2?.params?.get("query"))

        // Index Intent
        val res3 = IntentResolver.resolve("index urgent meeting action items")
        assertNotNull(res3)
        assertEquals(AssistantIntent.RAG_INDEX, res3?.intent)
        assertEquals("urgent meeting action items", res3?.params?.get("content"))
    }

    @Test
    fun testAgentContextRouterRagRouting() {
        val store = InMemoryRagIndexStore()
        val chunker = DocumentChunker()
        val text = "Alpha protocol requires encryption keys rotated every 30 days."
        val (meta, chunks) = chunker.chunk(text, "doc_alpha", "Alpha Protocol", "/docs/alpha.txt")
        store.indexDocument(meta, chunks)

        val retriever = RagRetriever(store)
        val injector = RagContextInjector(retriever)
        val router = AgentContextRouter(ragInjector = injector)

        val needs = router.routeNeeds("What does the alpha protocol document say?")
        assertTrue(needs.contains(ContextNeed.KNOWLEDGE_VAULT))

        val state = AgentState(taskId = 1, goal = "alpha protocol document")
        val workingMemory = AgentWorkingMemory(goal = "alpha protocol document")
        val builtContext = router.buildContext("alpha protocol document", state, workingMemory)

        assertTrue(builtContext.contains("=== RAG KNOWLEDGE VAULT: RETRIEVED EVIDENCE ==="))
        assertTrue(builtContext.contains("Alpha Protocol"))
    }
}
