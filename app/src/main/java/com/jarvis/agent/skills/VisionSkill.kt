package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

enum class VisionMode {
    SNAP_IDENTIFY,
    LIVE_OCR,
    LIVE_PERCEPTION
}

open class VisionSkill(private val opticalOnly: Boolean = false) : Skill {
    override val id: String = if (opticalOnly) "camera_optical_vision" else "vision"
    override val name: String = if (opticalOnly) "Camera Optical Vision & Object Perception Skill" else "Universal Vision Skill"
    override val description: String = "Understands and answers questions about device screen or optical surroundings via camera."

    override val triggers: List<String> = listOf(
        // English screen triggers
        "what is on my screen", "what's on my screen", "look at my screen",
        "look at this", "look at the screen", "read my screen", "read the screen",
        "read this", "read this message", "read this text", "summarize screen",
        "summarize this", "what does this say", "what is this", "check screen",
        "analyze screen", "see screen", "see this",
        // English optical / camera triggers
        "what am i looking at", "what's this object", "what is this object",
        "what is in front of me", "identify this object", "describe what you see",
        "read this label", "read this paper", "scan this sign", "what is this thing",
        "take a look", "camera look", "camera scan", "look around", "what is this in my hand",
        "read this medicine", "identify this plant", "read this book",
        "live ocr", "live text", "scan live", "continuous scan", "ocr mode",
        // Hindi / Hinglish screen triggers
        "is screen pe kya hai", "screen pe kya hai", "ye screen dekho",
        "screen dekho", "ye dekh ke batao", "ye padh ke batao", "padh ke batao",
        "kya likha hai", "screen samjhao", "ye samjhao", "screen pe dekho",
        "kya chal raha hai screen pe", "is screen ko dekho",
        // Hindi / Hinglish camera triggers
        "ye kya hai", "camera se dekho", "ye kaunsi cheez hai", "samne kya hai",
        "camera on karke dekho", "ye kiska photo hai", "kya dikh raha hai",
        "dekh ke batao", "samne dekho", "camera se padho", "live text padho"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()

        val isScreenQuery = lower.contains("on my screen") || lower.contains("is screen pe") || lower.contains("screen pe kya")
        if (opticalOnly && isScreenQuery) {
            return false
        }

        if (triggers.any { lower.contains(it) }) {
            return true
        }

        val hasCameraWord = lower.contains("camera") || lower.contains("lens") || lower.contains("photo") || lower.contains("selfie") || lower.contains("picture")
        val hasVisionWord = lower.contains("dekho") || lower.contains("look") || lower.contains("see") ||
                lower.contains("read") || lower.contains("identify") || lower.contains("kya hai") ||
                lower.contains("scan") || lower.contains("ocr") || lower.contains("padho") || lower.contains("take")

        if (hasCameraWord && hasVisionWord) return true

        return (!opticalOnly && (lower.contains("screen") || lower.contains("ye ")) &&
                (lower.contains("padho") || lower.contains("dekho") || lower.contains("read") || lower.contains("look") || lower.contains("what")))
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        val isCameraTarget = opticalOnly ||
                lower.contains("camera") || lower.contains("lens") || lower.contains("selfie") ||
                lower.contains("in front of me") || lower.contains("sign") || lower.contains("object") ||
                lower.contains("samne") || lower.contains("label") || lower.contains("live ocr") ||
                lower.contains("ocr mode") || lower.contains("flash") || lower.contains("photo")

        if (!isCameraTarget && (lower.contains("screen") || !opticalOnly)) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "SCREEN_VISION",
                    params = mapOf("query" to goal)
                ),
                explanation = "Visually analyzing screen to answer user query."
            )
        }

        val facing = if (lower.contains("selfie") || lower.contains("my face") || lower.contains("front camera")) "front" else "back"
        val flash = if (lower.contains("flash") || lower.contains("torch") || lower.contains("dark")) "on" else "auto"
        val mode = determineVisionMode(lower)

        val params = mutableMapOf(
            "query" to goal,
            "mode" to mode.name,
            "facing" to facing,
            "flash" to flash
        )

        val explanation = when (mode) {
            VisionMode.LIVE_OCR -> "Starting camera live-stream OCR to continuously read text and labels"
            VisionMode.LIVE_PERCEPTION -> "Starting camera real-time perception HUD for object tracking"
            VisionMode.SNAP_IDENTIFY -> "Capturing camera snapshot ($facing lens) to visually analyze surroundings for: \"$goal\""
        }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(
                type = "CAMERA_VISION",
                params = params
            ),
            explanation = explanation
        )
    }

    companion object {
        fun determineVisionMode(lower: String): VisionMode {
            val isLive = lower.contains("live") || lower.contains("continuous") || lower.contains("stream")
            val isOcr = lower.contains("ocr") || lower.contains("read") || lower.contains("padho") || lower.contains("text") || lower.contains("label") || lower.contains("paper")

            return when {
                isLive && isOcr -> VisionMode.LIVE_OCR
                isLive -> VisionMode.LIVE_PERCEPTION
                isOcr && (lower.contains("scan") || lower.contains("mode")) -> VisionMode.LIVE_OCR
                else -> VisionMode.SNAP_IDENTIFY
            }
        }
    }
}

class CameraVisionSkill : VisionSkill(opticalOnly = true) {
    companion object {
        fun determineVisionMode(lower: String): VisionMode = VisionSkill.determineVisionMode(lower)
    }
}
