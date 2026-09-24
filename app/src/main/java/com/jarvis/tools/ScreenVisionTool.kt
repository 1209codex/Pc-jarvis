package com.jarvis.tools

import android.content.Context
import android.util.Log
import com.jarvis.accessibility.ScreenCaptureResult
import com.jarvis.accessibility.ScreenVisionManager
import com.jarvis.ai.LlmClient
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class ScreenVisionTool(
    private val context: Context? = null,
    private val llmClient: LlmClient? = null
) : Tool {

    override val name: String = "SCREEN_VISION"
    override val description: String = "Visually captures and analyzes what is currently displayed on the device screen."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "SCREEN_VISION",
        description = "Inspects and understands the active screen using multimodal vision and UI accessibility tree.",
        parameters = listOf(
            com.jarvis.foundation.ParameterSchema("query", "string", "What to look for, read, or summarize on screen", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.ifBlank { null } ?: "Describe what is currently displayed on the screen and highlight important information or text."
        val rawMode = params["mode"]?.trim()?.lowercase()
        val mode = when {
            !rawMode.isNullOrBlank() -> rawMode
            query.contains("summariz", ignoreCase = true) || query.contains("short", ignoreCase = true) -> "summarize"
            query.contains("error", ignoreCase = true) || query.contains("crash", ignoreCase = true) || query.contains("fail", ignoreCase = true) -> "error_check"
            query.contains("extract", ignoreCase = true) || query.contains("otp", ignoreCase = true) || query.contains("code", ignoreCase = true) -> "extract"
            else -> "explain"
        }

        // 1. Capture screen (Hardware screenshot bitmap on API 30+ & UI Text Tree)
        val captureResult = ScreenVisionManager.captureScreen()

        Log.i(TAG, "Screen captured: mode=$mode, isVisual=${captureResult.isVisualAvailable}, activeApp=${captureResult.activePackage}")

        // 2. Multimodal LLM Reasoning
        val analysis = if (llmClient != null) {
            val systemPrompt = when (mode) {
                "summarize" -> """
                    You are JARVIS on-device Visual Copilot specializing in content summarization.
                    Analyze the screen content and condense the visible article, conversation, or post into 2 concise, clear sentences suitable for speech synthesis.
                    User Query: "$query"
                """.trimIndent()
                "error_check" -> """
                    You are JARVIS on-device Visual Copilot specializing in error diagnosis.
                    Analyze the screen content, identify any error dialogs, crashes, warnings, or failures.
                    Concisely state what went wrong and recommend the next actionable step in 1-2 sentences.
                    User Query: "$query"
                """.trimIndent()
                "extract" -> """
                    You are JARVIS on-device Visual Copilot specializing in data extraction.
                    Directly extract only the requested number, code, price, OTP, or address without introductory filler.
                    User Query: "$query"
                """.trimIndent()
                else -> """
                    You are JARVIS on-device Screen Vision Assistant.
                    Analyze the provided screenshot and/or UI accessibility context to directly and concisely answer the user's query:
                    Query: "$query"

                    Rules:
                    - Be concise, natural, and direct.
                    - Focus on what the user specifically asked.
                    - Mention the active application name if relevant.
                    - Keep the response within 2-4 sentences suitable for speech synthesis.
                """.trimIndent()
            }

            val result = llmClient.chatVision(
                prompt = systemPrompt,
                base64ImageUrl = captureResult.base64Image,
                uiContext = captureResult.uiTextTree
            )
            result.getOrElse { err ->
                Log.w(TAG, "Vision LLM failed: ${err.message}, using UI tree fallback")
                fallbackTreeSummary(captureResult, query, mode)
            }
        } else {
            fallbackTreeSummary(captureResult, query, mode)
        }

        return ToolResult.Success(
            message = analysis,
            data = mapOf(
                "query" to query,
                "mode" to mode,
                "active_package" to (captureResult.activePackage ?: "Unknown"),
                "visual_available" to captureResult.isVisualAvailable,
                "elements_count" to captureResult.elements.size,
                "screen_width" to captureResult.screenWidth,
                "screen_height" to captureResult.screenHeight
            )
        )
    }

    private fun fallbackTreeSummary(capture: ScreenCaptureResult, query: String, mode: String): String {
        val app = formatAppName(capture.activePackage)
        val textLines = capture.uiTextTree
            .lines()
            .filter { line ->
                !line.startsWith("Foreground App:") &&
                !line.startsWith("Visible UI Elements") &&
                !line.contains("No interactive UI text") &&
                line.isNotBlank()
            }
            .map { line ->
                line.substringAfter("] ", line)
                    .replace(Regex("\\[|\\]|\\(clickable=.*?\\)|\\(editable=.*?\\)"), "")
                    .replace(Regex("^[0-9]+\\.\\s*"), "")
                    .trim()
            }
            .filter { it.length > 1 && !it.startsWith("com.") && !it.contains(":id/") }
            .distinct()

        if (textLines.isEmpty()) {
            return "You are currently on $app. No prominent text was found on the active screen."
        }

        return when (mode) {
            "error_check" -> {
                val errorLines = textLines.filter { line ->
                    listOf("error", "failed", "crash", "denied", "exception", "wrong", "unable", "invalid")
                        .any { line.contains(it, ignoreCase = true) }
                }
                if (errorLines.isNotEmpty()) {
                    "Alert on $app: \"${errorLines.first()}\"."
                } else {
                    "No explicit errors detected on $app. Visible elements: ${textLines.take(3).joinToString(", ")}."
                }
            }
            "summarize" -> {
                "Summary on $app: ${textLines.take(3).joinToString(". ")}."
            }
            "extract" -> {
                val codeMatch = textLines.firstOrNull { it.matches(Regex(".*\\b([0-9]{4,8})\\b.*")) }
                if (codeMatch != null) {
                    val digits = Regex("\\b([0-9]{4,8})\\b").find(codeMatch)?.groupValues?.get(1)
                    "Extracted code: $digits on $app."
                } else {
                    "Found text: ${textLines.firstOrNull() ?: "none"} on $app."
                }
            }
            else -> {
                "You are on $app. Visible on screen: ${textLines.take(6).joinToString(", ")}."
            }
        }
    }

    private fun formatAppName(pkg: String?): String {
        if (pkg == null) return "your screen"
        return when {
            pkg.contains("launcher", ignoreCase = true) -> "Home Screen"
            pkg.contains("youtube", ignoreCase = true) -> "YouTube"
            pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            pkg.contains("settings", ignoreCase = true) -> "Settings"
            pkg.contains("chrome", ignoreCase = true) -> "Chrome"
            pkg.contains("camera", ignoreCase = true) -> "Camera"
            pkg.contains("gallery", ignoreCase = true) -> "Gallery"
            pkg.contains("clock", ignoreCase = true) -> "Clock"
            pkg.contains("calendar", ignoreCase = true) -> "Calendar"
            pkg.contains("calculator", ignoreCase = true) -> "Calculator"
            pkg.contains("spotify", ignoreCase = true) -> "Spotify"
            pkg.contains("jarvis", ignoreCase = true) -> "JARVIS"
            else -> pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        private const val TAG = "ScreenVisionTool"
    }
}
