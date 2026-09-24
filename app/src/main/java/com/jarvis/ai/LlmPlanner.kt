package com.jarvis.ai

import android.util.Log
import com.jarvis.agent.*
import com.jarvis.foundation.MetricsCollector
import org.json.JSONObject

data class ActionPlan(
    val type: String,
    val params: Map<String, String>
)

data class ExecutionPlan(
    val response: String,
    val actions: List<ActionPlan>
)

class LlmPlanner(
    private val llmClient: LlmClient,
    private val router: ModelRouter? = null,
    private val strongLlm: LlmClient? = null
) {
    private val TAG = "LlmPlanner"

    suspend fun decide(
        history: List<Message>,
        goal: String,
        state: AgentState,
        workingMemory: AgentWorkingMemory,
        context: String,
        availableTools: String,
        onToken: (String) -> Unit = {}
    ): Result<AgentDecision> {
        val route = router?.route(goal)
        if (route != null) {
            MetricsCollector.shared.record("router", route.tier.name.lowercase(), ok = route.usesCloud, wallMs = 0)
            if (route.privacySensitive) {
                return Result.failure(
                    IllegalStateException("This request looks privacy-sensitive; refusing to send it to a cloud model.")
                )
            }
        }
        val activeLlm = if (route?.tier == ModelTier.STRONG && strongLlm != null) strongLlm else llmClient
        val prompt = buildDecisionPrompt(goal, state, workingMemory, context, availableTools)
        val messages = mutableListOf<Message>()
        messages.add(Message("system", prompt))
        // When a privacy router is active, never ship earlier transcript turns that carry
        // sensitive content (passwords, personal numbers) — strip them before sending.
        val recentWindow = history.takeLast(6)
        val recentHistory = if (route != null) {
            recentWindow.filterNot { com.jarvis.foundation.SensitiveTerms.contains(it.content) }
        } else {
            recentWindow
        }
        messages.addAll(recentHistory)
        messages.add(Message("user", "Current Goal: $goal\nStep #${state.iteration}: What is the next best decision?"))

        val result = activeLlm.chat(messages, onToken)
        return result.mapCatching { rawContent -> parseDecision(rawContent, goal) }
    }

    fun parseDecision(rawText: String, goal: String = ""): AgentDecision {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Empty decision response from LLM")
        }

        val jsonString = extractJsonSubstring(trimmed)
        if (jsonString.startsWith("{") && jsonString.endsWith("}")) {
            try {
                val json = JSONObject(jsonString)
                val decisionStr = json.optString("decision", "ACT").uppercase()
                val decisionType = runCatching { DecisionType.valueOf(decisionStr) }.getOrElse { DecisionType.ACT }
                val reasonCode = json.optString("reason_code", "llm_plan")
                val question = if (json.has("question")) json.getString("question") else null
                val confidence = json.optDouble("confidence", 1.0).toFloat()

                val successCrit = mutableListOf<String>()
                val critArr = json.optJSONArray("success_criteria")
                if (critArr != null) {
                    for (i in 0 until critArr.length()) successCrit.add(critArr.getString(i))
                }

                var action: AgentAction? = null
                val actObj = json.optJSONObject("action")
                if (actObj != null) {
                    val rawActType = actObj.optString("type", "").uppercase().trim()
                    val actType = when (rawActType) {
                        "TORCH", "LIGHT" -> "FLASHLIGHT"
                        "CALL", "DIAL", "SMS", "SEND_SMS" -> "TELEPHONY_CONTROL"
                        "WHATSAPP_MESSAGE", "SEND_WHATSAPP" -> "WHATSAPP"
                        "STOP_MUSIC", "PAUSE_MUSIC" -> "MEDIA_STOP"
                        "GOOGLE", "SEARCH" -> "SEARCH_WEB"
                        "YOUTUBE" -> "YOUTUBE_PLAY"
                        else -> rawActType
                    }
                    val paramsMap = mutableMapOf<String, String>()
                    val paramsObj = actObj.optJSONObject("params")
                    if (paramsObj != null) {
                        paramsObj.keys().forEach { k -> paramsMap[k] = paramsObj.optString(k, "") }
                    } else {
                        actObj.keys().forEach { k -> if (k != "type") paramsMap[k] = actObj.optString(k, "") }
                    }
                    if (actType.isNotBlank()) {
                        action = AgentAction(type = actType, params = paramsMap)
                    }
                }

                if (action != null && confidence < 0.55f) {
                    Log.w(TAG, "Rejecting low-confidence action ($confidence) for goal '$goal'")
                    return AgentDecision(
                        type = DecisionType.ASK_USER,
                        question = "I’m not confident I heard that correctly. Please repeat the command.",
                        reasonCode = "low_confidence_action",
                        confidence = confidence
                    )
                }

                return AgentDecision(
                    type = decisionType,
                    action = action,
                    question = question,
                    reasonCode = reasonCode,
                    successCriteria = successCrit,
                    confidence = confidence
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing extracted JSON: ${e.message}, checking conversational fallback")
            }
        }

        // If the LLM returned conversational plain text instead of strict JSON:
        val cleanText = trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val lowerGoal = goal.lowercase().trim()
        val isActionableGoal = lowerGoal.isNotBlank() && MultiTaskDecomposer.hasActionVerb(lowerGoal)

        if (isActionableGoal) {
            // Check if cleanText accidentally contains an unclosed or embedded JSON object
            val embeddedJson = extractJsonSubstring(cleanText)
            if (embeddedJson.startsWith("{") && embeddedJson.endsWith("}")) {
                val parsed = runCatching { parseDecision(embeddedJson, goal) }.getOrNull()
                if (parsed != null && parsed.action != null) return parsed
            }
            throw IllegalStateException("LLM produced conversational text without structured action for actionable goal: '$goal'")
        }

        return AgentDecision(
            type = DecisionType.ACT,
            action = AgentAction(type = "SPEAK", params = mapOf("text" to cleanText)),
            question = null,
            reasonCode = "llm_conversational_text",
            successCriteria = listOf("Spoke LLM answer to user"),
            confidence = 0.9f
        )
    }

    private fun buildDecisionPrompt(
        goal: String,
        state: AgentState,
        workingMemory: AgentWorkingMemory,
        context: String,
        availableTools: String
    ): String {
        return """
            You are the JARVIS Agent Decision Engine.
            Your job is to accomplish the user's goal.
            You are choosing the NEXT best decision, not generating an unverified story about what already happened.

            Available decisions:
            ACT
            RESEARCH
            RETRIEVE_MEMORY
            RETRIEVE_KNOWLEDGE
            ASK_USER
            RETRY
            RECOVER
            COMPLETE
            FAIL

            Rules:
            1. Understand the user's goal.
            2. Use only relevant context.
            3. Use memory when personalization or previous experience is relevant.
            4. Use research when current/external information is required.
            5. Use local knowledge when applicable.
            6. Select only tools that actually exist in AVAILABLE TOOLS.
            7. Never invent tool names.
            8. Never claim a tool succeeded if the result says otherwise.
            9. Never claim the user goal is complete without sufficient verification.
            10. After every action, use the returned observation and verification result.
            11. If a strategy failed, do not blindly repeat it.
            12. When appropriate, change query, target, tool, or strategy.
            13. Ask the user when ambiguity materially affects the action.
            14. Respect permissions and policy.
            15. Do not perform duplicate non-idempotent actions after uncertain failures.
            16. Stop when the actual user goal is satisfied.
            17. Keep reasoning concise and operational.
            18. Return strict JSON only.

            USER GOAL: $goal
            TASK ITERATION: ${state.iteration}
            LAST ACTION: ${state.lastAction?.type ?: "None"} (${state.lastAction?.params ?: ""})
            LAST OBSERVATION: ${state.lastObservation?.message ?: "None"}
            LAST TOOL RESULT: ${workingMemory.lastToolResult ?: "None"}
            CANDIDATES: ${workingMemory.candidates.joinToString("; ").ifBlank { "None" }}

            CONTEXT & EVIDENCE:
            $context

            AVAILABLE TOOLS:
            $availableTools

            REQUIRED DECISION JSON FORMAT (Return ONLY raw JSON):
            {
              "decision": "ACT",
              "reason_code": "STRING",
              "action": {
                "type": "TOOL_NAME",
                "params": {}
              },
              "success_criteria": ["criteria 1"],
              "confidence": 0.95
            }
        """.trimIndent()
    }

    private fun extractJsonSubstring(input: String): String {
        val trimmed = input.trim()
        val withoutFences = if (trimmed.startsWith("```")) {
            trimmed.substringAfter("\n").substringBeforeLast("```").trim()
        } else {
            trimmed
        }
        val start = withoutFences.indexOf('{')
        val end = withoutFences.lastIndexOf('}')
        return if (start != -1 && end > start) withoutFences.substring(start, end + 1) else withoutFences
    }
}
