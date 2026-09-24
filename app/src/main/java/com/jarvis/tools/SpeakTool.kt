package com.jarvis.tools

import com.jarvis.voice.TtsEngine

class SpeakTool(
    private val ttsEngine: TtsEngine? = null,
    var onSpoken: ((String) -> Unit)? = null
) : Tool {
    override val name: String = "SPEAK"
    override val description: String = "Speaks text response aloud to the user using TTS. Parameter: text (spoken content)."
    override val policy: ToolPolicy = ToolPolicy(timeoutMs = 60_000L)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val text = params["text"] ?: params["response"] ?: return ToolResult(false, "Speech text missing")
        if (text.isBlank()) return ToolResult(true, "Empty speech text skipped")

        onSpoken?.invoke(text)
        // ttsEngine.speak() already handles completion tracking, timeout, and audio focus.
        val ok = ttsEngine?.speak(text) ?: true
        return ToolResult(ok, if (ok) text else "TTS failed or timed out")
    }
}
