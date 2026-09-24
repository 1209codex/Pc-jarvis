package com.jarvis.tools

import android.content.Context
import android.util.Log
import com.jarvis.ai.LlmClient
import com.jarvis.camera.CameraCaptureResult
import com.jarvis.camera.CameraPerceptionEngine
import com.jarvis.camera.TfLiteVisionDetector
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class CameraVisionTool(
    private val context: Context? = null,
    private val perceptionEngine: CameraPerceptionEngine = CameraPerceptionEngine(context),
    private val detector: TfLiteVisionDetector = TfLiteVisionDetector(),
    private val llmClient: LlmClient? = null
) : Tool {

    private val TAG = "CameraVisionTool"

    override val name: String = "CAMERA_VISION"
    override val description: String =
        "Captures live camera snapshot (back or front lens) and visually understands objects, scenes, documents, text, and surroundings. Params: query (optional), facing (back/front), flash (auto/on/off)."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "CAMERA_VISION",
        description = "Understands real-world physical surroundings, objects, and text using device camera and multimodal vision AI.",
        parameters = listOf(
            ParameterSchema("query", "string", "What to identify, inspect, or read in front of the lens", required = false),
            ParameterSchema("facing", "string", "Camera facing: 'back' (default) or 'front'/'selfie'", required = false),
            ParameterSchema("flash", "string", "Flash mode: 'auto', 'on', or 'off'", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim()?.ifBlank { null }
            ?: params["text"]?.trim()?.ifBlank { null }
            ?: "Describe what is currently in front of the camera and identify the main object or text."

        val facing = params["facing"]?.trim()?.lowercase()
            ?: params["lens"]?.trim()?.lowercase()
            ?: if (query.contains("selfie", ignoreCase = true) || query.contains("my face", ignoreCase = true)) "front" else "back"

        val flashEnabled = params["flash"]?.equals("on", ignoreCase = true) == true

        Log.i(TAG, "Executing CAMERA_VISION: query=\"$query\", facing=$facing, flash=$flashEnabled")

        // 1. Capture snapshot frame
        val captureResult: CameraCaptureResult = try {
            perceptionEngine.captureFrame(facing = facing, enableFlash = flashEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}", e)
            return ToolResult.Failed("Camera capture failed: ${e.message}")
        }

        val bitmap = captureResult.bitmap
        val base64 = captureResult.base64Jpeg
        if (bitmap == null && base64.isNullOrBlank()) {
            val err = captureResult.errorMessage ?: "Failed to acquire camera frame"
            return ToolResult.Failed(err)
        }

        // 2. Run on-device edge perception
        val sceneAnalysis = detector.analyze(bitmap)
        Log.i(TAG, "Edge detection: ${sceneAnalysis.summary}")

        // 3. Reason via Multimodal Cloud Vision LLM
        if (llmClient != null && !base64.isNullOrBlank()) {
            val prompt = """
                You are JARVIS Physical World Optical Perception Assistant.
                The user has pointed their device camera ($facing lens) at an object, document, or scene.
                
                User Query: "$query"
                Edge Sensor Cues: ${sceneAnalysis.summary}
                Lighting: ${sceneAnalysis.lightingCondition}
                
                Instructions:
                - Directly identify the focal object or answer the user's question.
                - If reading text (signs, book, label, screen), clearly transcribe and summarize the key information.
                - Keep the response concise, clear, and direct (2 to 3 sentences max) tailored for voice speech synthesis.
                - Do not mention technical camera parameters unless poor lighting affects visibility.
            """.trimIndent()

            val visionResult = llmClient.chatVision(
                prompt = prompt,
                base64ImageUrl = base64
            )

            val visualAnswer = visionResult.getOrNull()
            if (!visualAnswer.isNullOrBlank()) {
                return ToolResult.Success(visualAnswer.trim())
            } else {
                Log.w(TAG, "Vision LLM failed, using edge detection fallback: ${visionResult.exceptionOrNull()?.message}")
            }
        }

        // 4. Edge fallback response
        val fallbackResponse = "I see ${sceneAnalysis.primaryCategory.lowercase()} in front of the camera (${sceneAnalysis.lightingCondition}). " +
                "Detected: ${sceneAnalysis.detections.joinToString { it.label }}."
        return ToolResult.Success(fallbackResponse)
    }
}
