package com.jarvis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.CameraVisionSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.ai.LlmClient
import com.jarvis.ai.Message
import com.jarvis.camera.CameraPerceptionEngine
import com.jarvis.camera.TfLiteVisionDetector
import com.jarvis.tools.CameraVisionTool
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraVisionTest {

    private class MockVisionLlm(private val response: String) : LlmClient {
        override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> =
            Result.success(response)

        override suspend fun chatVision(
            prompt: String,
            base64ImageUrl: String?,
            uiContext: String?,
            onToken: (String) -> Unit
        ): Result<String> = Result.success(response)

        override fun cancel() {}
    }

    @Test
    fun testTfLiteVisionDetectorClassification() {
        val detector = TfLiteVisionDetector()

        val defaultAnalysis = detector.defaultAnalysis()
        assertEquals("General Scene", defaultAnalysis.primaryCategory)
        assertTrue(defaultAnalysis.detections.isNotEmpty())
        assertTrue(defaultAnalysis.brightness in 0f..1f)

        val nullAnalysis = detector.analyze(null)
        assertEquals("General Scene", nullAnalysis.primaryCategory)
        assertTrue(nullAnalysis.summary.contains("Detected"))
    }

    @Test
    fun testCameraPerceptionEngineSyntheticFrame() {
        val engine = CameraPerceptionEngine()
        val result = engine.generateSyntheticFrame(facing = "front", reason = "unit-test")

        assertTrue(result.isSuccess)
        assertEquals("front", result.lensFacing)
        assertEquals(640, result.width)
        assertEquals(480, result.height)
        assertNotNull(result.base64Jpeg)
        assertTrue(result.base64Jpeg!!.startsWith("data:image/jpeg;base64,"))

        val brightness = engine.calculateAverageBrightness(result.bitmap)
        assertTrue(brightness in 0f..1f)
    }

    @Test
    fun testCameraVisionToolExecutionWithMockLlm() = runBlocking {
        val mockLlm = MockVisionLlm("I can see an open programming textbook on the desk titled 'Kotlin In Action'.")
        val engine = CameraPerceptionEngine()
        val detector = TfLiteVisionDetector()
        val tool = CameraVisionTool(perceptionEngine = engine, detector = detector, llmClient = mockLlm)

        val result = tool.execute(mapOf("query" to "What book is this?"))
        assertTrue(result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue(msg.contains("Kotlin In Action"))
    }

    @Test
    fun testCameraVisionToolExecutionFallbackWithoutLlm() = runBlocking {
        val engine = CameraPerceptionEngine()
        val detector = TfLiteVisionDetector()
        val tool = CameraVisionTool(perceptionEngine = engine, detector = detector, llmClient = null)

        val result = tool.execute(emptyMap())
        assertTrue(result is ToolResult.Success)
        val msg = (result as ToolResult.Success).message
        assertTrue(msg.contains("Detected"))
    }

    @Test
    fun testCameraVisionSkillBilingualMatching() = runBlocking {
        val skill = CameraVisionSkill()
        val dummyContext = SkillContext("test", AgentWorkingMemory("test"))

        // English triggers
        assertTrue(skill.canHandle("what am i looking at", dummyContext))
        assertTrue(skill.canHandle("look at this object", dummyContext))
        assertTrue(skill.canHandle("identify this object", dummyContext))
        assertTrue(skill.canHandle("read this label", dummyContext))
        assertTrue(skill.canHandle("describe what you see", dummyContext))

        // Hindi / Hinglish triggers
        assertTrue(skill.canHandle("ye kya hai", dummyContext))
        assertTrue(skill.canHandle("camera se dekho", dummyContext))
        assertTrue(skill.canHandle("ye dekh ke batao", dummyContext))
        assertTrue(skill.canHandle("samne kya hai", dummyContext))
        assertTrue(skill.canHandle("camera on karke dekho", dummyContext))

        // Screen queries MUST be rejected (so ScreenVision handles them)
        assertFalse(skill.canHandle("what is on my screen", dummyContext))
        assertFalse(skill.canHandle("is screen pe kya hai", dummyContext))

        // Execution inspection
        val backResult = skill.execute("what am i looking at", dummyContext)
        assertTrue(backResult.handled)
        assertEquals("CAMERA_VISION", backResult.proposedAction?.type)
        assertEquals("back", backResult.proposedAction?.params?.get("facing"))

        val selfieResult = skill.execute("what am i looking at with selfie camera", dummyContext)
        assertEquals("front", selfieResult.proposedAction?.params?.get("facing"))
    }

    @Test
    fun testToolRegistryCameraAliases() {
        val registry = ToolRegistry()
        val stubCamera = object : Tool {
            override val name: String = "CAMERA_VISION"
            override val description: String = ""
            override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("ok")
        }
        registry.register(stubCamera)

        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("CAMERA_LOOK"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("WHAT_AM_I_LOOKING_AT"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("CAMERA_IDENTIFY"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("CAMERA_SCAN"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("IDENTIFY_OBJECT"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("DESCRIBE_SCENE"))
        assertEquals("CAMERA_VISION", registry.resolveCanonicalToolName("READ_OBJECT"))
    }

    @Test
    fun testIntentResolverCameraVision() {
        val res1 = IntentResolver.resolve("what am i looking at")
        assertNotNull(res1)
        assertEquals(AssistantIntent.CAMERA_VISION, res1?.intent)

        val res2 = IntentResolver.resolve("camera se dekho")
        assertNotNull(res2)
        assertEquals(AssistantIntent.CAMERA_VISION, res2?.intent)

        val res3 = IntentResolver.resolve("camera scan")
        assertNotNull(res3)
        assertEquals(AssistantIntent.CAMERA_VISION, res3?.intent)

        val res4 = IntentResolver.resolve("what am i looking at with front camera")
        assertNotNull(res4)
        assertEquals(AssistantIntent.CAMERA_VISION, res4?.intent)
        assertEquals("front", res4?.params?.get("facing"))
    }
}
