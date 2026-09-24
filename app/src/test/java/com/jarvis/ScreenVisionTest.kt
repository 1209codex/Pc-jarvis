package com.jarvis

import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.VisionSkill
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.ai.LlmClient
import com.jarvis.ai.LlmConfig
import com.jarvis.ai.Message
import com.jarvis.tools.ScreenVisionTool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenVisionTest {

    private val skillContext = SkillContext(
        goal = "screen analysis",
        workingMemory = com.jarvis.agent.AgentWorkingMemory("screen analysis")
    )

    @Test
    fun testVisionSkillIntentRecognition() = runBlocking {
        val skill = VisionSkill()

        // English triggers
        assertTrue(skill.canHandle("What is on my screen?", skillContext))
        assertTrue(skill.canHandle("Jarvis, look at this screen", skillContext))
        assertTrue(skill.canHandle("Read this message for me", skillContext))
        assertTrue(skill.canHandle("Summarize this screen", skillContext))
        assertTrue(skill.canHandle("What does this say?", skillContext))

        // Hindi / Hinglish triggers
        assertTrue(skill.canHandle("is screen pe kya hai", skillContext))
        assertTrue(skill.canHandle("ye screen dekho", skillContext))
        assertTrue(skill.canHandle("ye padh ke batao", skillContext))
        assertTrue(skill.canHandle("kya likha hai yahan", skillContext))
        assertTrue(skill.canHandle("screen samjhao", skillContext))

        // Non-vision commands should not be handled
        assertFalse(skill.canHandle("open whatsapp", skillContext))
        assertFalse(skill.canHandle("play despacito on youtube", skillContext))
        assertFalse(skill.canHandle("turn on flashlight", skillContext))
    }

    @Test
    fun testVisionSkillExecutionProducesAction() = runBlocking {
        val skill = VisionSkill()
        val query = "What is the total price on this page?"
        val result = skill.execute(query, skillContext)

        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        assertEquals("SCREEN_VISION", result.proposedAction?.type)
        assertEquals(query, result.proposedAction?.params?.get("query"))
    }

    @Test
    fun testIntentResolverScreenVisionFastPath() {
        val englishIntent = IntentResolver.resolve("What is on my screen?")
        assertNotNull(englishIntent)
        assertEquals(AssistantIntent.SCREEN_VISION, englishIntent?.intent)
        assertEquals("SCREEN_VISION", englishIntent?.directPlan?.actions?.firstOrNull()?.type)

        // Simple single-intent Hindi query resolves to fast-path
        val hindiIntent = IntentResolver.resolve("ye screen dekho")
        assertNotNull(hindiIntent)
        assertEquals(AssistantIntent.SCREEN_VISION, hindiIntent?.intent)

        // Compound query with conjunction ("aur") bypasses fast-path so AgentKernel handles it
        val compoundIntent = IntentResolver.resolve("ye screen dekho aur batao kya hai")
        assertNull(compoundIntent)
    }

    @Test
    fun testToolRegistryVisionAliases() {
        val registry = ToolRegistry()
        val dummyTool = object : com.jarvis.tools.Tool {
            override val name: String = "SCREEN_VISION"
            override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("OK")
        }
        registry.register(dummyTool)

        assertEquals("SCREEN_VISION", registry.resolveCanonicalToolName("SCREEN_ANALYZE"))
        assertEquals("SCREEN_VISION", registry.resolveCanonicalToolName("SCREEN_READ"))
        assertEquals("SCREEN_VISION", registry.resolveCanonicalToolName("SCREEN_LOOK"))
        assertEquals("SCREEN_VISION", registry.resolveCanonicalToolName("SEE_SCREEN"))
        assertEquals("SCREEN_VISION", registry.resolveCanonicalToolName("VISION"))
    }

    @Test
    fun testVisionLlmConfiguration() {
        assertEquals("qwen/qwen3.6-27b", LlmConfig.DEFAULT_VISION_MODEL)
        assertTrue(LlmConfig.VISION_CANDIDATE_MODELS.contains("qwen/qwen3.6-27b"))
        assertTrue(LlmConfig.VISION_CANDIDATE_MODELS.contains("gemini-2.5-flash"))
        // Decommissioned vision models must not be present
        assertFalse(LlmConfig.VISION_CANDIDATE_MODELS.contains("llama-3.2-11b-vision-preview"))
        assertFalse(LlmConfig.VISION_CANDIDATE_MODELS.contains("llama-3.2-90b-vision-preview"))
        assertFalse(LlmConfig.VISION_CANDIDATE_MODELS.contains("gemini-2.0-flash"))
    }

    @Test
    fun testScreenVisionToolWithMockClient() = runBlocking {
        var capturedPrompt: String? = null

        val fakeClient = object : LlmClient {
            override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
                return Result.success("Standard chat reply")
            }

            override suspend fun chatVision(
                prompt: String,
                base64ImageUrl: String?,
                uiContext: String?,
                onToken: (String) -> Unit
            ): Result<String> {
                capturedPrompt = prompt
                return Result.success("This screen shows a YouTube video of Android tutorials.")
            }

            override fun cancel() {}
        }

        val tool = ScreenVisionTool(context = null, llmClient = fakeClient)
        val result = tool.execute(mapOf("query" to "What video is playing?"))

        assertTrue(result is ToolResult.Success)
        assertTrue(result.message.contains("YouTube video"))
        assertNotNull(capturedPrompt)
        assertTrue(capturedPrompt!!.contains("What video is playing?"))
    }

    @Test
    fun testScreenVisionToolFallbackWithoutLlm() = runBlocking {
        val tool = ScreenVisionTool(context = null, llmClient = null)
        val result = tool.execute(mapOf("query" to "Explain this screen"))

        assertTrue(result is ToolResult.Success)
        assertTrue(result.message.isNotEmpty())
    }
}
