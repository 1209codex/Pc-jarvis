package com.jarvis

import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.foundation.PolicyEngine
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.runtime.MemoryNamespaces
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantRuntimeTest {

    private class EchoTool(override val name: String) : Tool {
        override val policy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)
        override suspend fun execute(params: Map<String, String>): ToolResult {
            return ToolResult.Success("Echoed: ${params["msg"] ?: "ok"}")
        }
    }

    @Test
    fun testLocalUserAndNamespacesContracts() {
        assertEquals("documents", MemoryNamespaces.DOCUMENTS)
        assertEquals("conversation", MemoryNamespaces.CONVERSATION)
        assertEquals("long_term", MemoryNamespaces.LONG_TERM)
    }

    @Test
    fun testIntentResolverFastPath() {
        // App open
        val openYt = IntentResolver.resolve("open youtube")
        assertNotNull(openYt)
        assertEquals(AssistantIntent.APP_OPEN, openYt?.intent)
        assertEquals("youtube", openYt?.params?.get("app"))

        // Play music
        val playMusic = IntentResolver.resolve("play kesariya")
        assertNotNull(playMusic)
        assertEquals(AssistantIntent.PLAY_MEDIA, playMusic?.intent)
        assertEquals("kesariya", playMusic?.params?.get("query"))

        // Media stop
        val stopMedia = IntentResolver.resolve("stop music")
        assertNotNull(stopMedia)
        assertEquals(AssistantIntent.STOP_MEDIA, stopMedia?.intent)

        // Web search
        val search = IntentResolver.resolve("search quantum computing")
        assertNotNull(search)
        assertEquals(AssistantIntent.SEARCH_WEB, search?.intent)
        assertEquals("quantum computing", search?.params?.get("query"))

        // Memory store
        val note = IntentResolver.resolve("remember that my wifi password is test")
        assertNotNull(note)
        assertEquals(AssistantIntent.REMEMBER_FACT, note?.intent)
    }

    @Test
    fun testToolRegistryAndPolicyExecution() = runBlocking {
        val registry = ToolRegistry()
        val tool = EchoTool("ECHO")
        registry.register(tool)

        assertNotNull(registry.get("ECHO"))
        assertEquals("ECHO", registry.get("ECHO")?.name)

        val policy = PolicyEngine() // default: autonomousFullAuto = false (fail-closed)
        val metaLow = ToolMetadata("ECHO", "Echo test tool", emptyList(), RiskLevel.LOW)
        val evalLow = policy.evaluate(metaLow, emptyMap())
        assertTrue(evalLow.allowed)
        assertFalse(evalLow.requiresApproval)

        val metaHigh = ToolMetadata("DELETE_DATA", "High risk action", emptyList(), RiskLevel.HIGH)
        val evalHigh = policy.evaluate(metaHigh, emptyMap())
        assertTrue("High risk action is allowed conditionally", evalHigh.allowed)
        assertTrue("High risk action MUST require explicit user approval in default mode", evalHigh.requiresApproval)

        val metaCritical = ToolMetadata("FORMAT_DEVICE", "Critical destructive action", emptyList(), RiskLevel.CRITICAL)
        val evalCritical = policy.evaluate(metaCritical, emptyMap())
        assertFalse("Critical destructive action must NEVER be allowed unconditionally", evalCritical.allowed)
        assertTrue(evalCritical.requiresApproval)
    }
}
