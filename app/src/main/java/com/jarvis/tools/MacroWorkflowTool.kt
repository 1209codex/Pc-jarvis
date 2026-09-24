package com.jarvis.tools

import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.macro.MacroWorkflowEngine

class MacroWorkflowTool(private val macroEngine: MacroWorkflowEngine) : Tool {
    override val name: String = "MACRO_WORKFLOW"
    override val description: String =
        "Executes multi-step UI automation workflows and app macros. Parameters: action ('run', 'list', 'info', 'delete'), macro ('clear_apps', 'youtube_search', 'software_update', etc.), query/payload (optional dynamic input)."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = false, riskLevel = RiskLevel.MEDIUM, timeoutMs = 120_000L)

    val metadata = ToolMetadata(
        name = "MACRO_WORKFLOW",
        description = "Runs autonomous multi-step app navigation and macro UI routines.",
        parameters = emptyList(),
        riskLevel = RiskLevel.MEDIUM
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "run"
        val macroTarget = params["macro"] ?: params["macro_id"] ?: params["name"] ?: ""

        return when (action) {
            "list" -> {
                val all = macroEngine.getAllMacros()
                if (all.isEmpty()) {
                    ToolResult.Success("No UI macros configured.")
                } else {
                    val summary = buildString {
                        append("Configured Autonomous UI Macros (${all.size}):\n")
                        all.forEachIndexed { idx, m ->
                            append("${idx + 1}. ${m.name} [${m.steps.size} steps] - ${m.description}\n")
                        }
                    }.trim()
                    ToolResult.Success(
                        message = summary,
                        data = mapOf("count" to all.size, "macros" to all.map { it.name })
                    )
                }
            }

            "run", "execute", "trigger" -> {
                if (macroTarget.isBlank()) {
                    return ToolResult.Failed("Specify the macro name or ID to run (e.g. 'clear_apps', 'youtube_search', 'software_update')")
                }
                val macro = macroEngine.getMacro(macroTarget)
                    ?: return ToolResult.Failed("No macro found matching '$macroTarget'")

                val result = macroEngine.executeMacro(macro, dynamicParams = params)
                if (result.success) {
                    ToolResult.Success(
                        message = "Macro '${macro.name}' executed successfully (${result.completedSteps}/${result.totalSteps} steps).",
                        data = mapOf(
                            "macro" to macro.name,
                            "completed_steps" to result.completedSteps,
                            "total_steps" to result.totalSteps,
                            "logs" to result.stepLogs
                        )
                    )
                } else {
                    ToolResult.Failed("Macro '${macro.name}' failed: ${result.failureReason}")
                }
            }

            "info", "get" -> {
                if (macroTarget.isBlank()) return ToolResult.Failed("Specify macro name to view info")
                val macro = macroEngine.getMacro(macroTarget)
                    ?: return ToolResult.Failed("Macro '$macroTarget' not found")

                val details = buildString {
                    append("Macro: ${macro.name} (${macro.id})\n")
                    append("Description: ${macro.description}\n")
                    append("Steps (${macro.steps.size}):\n")
                    macro.steps.forEach { s ->
                        append("  ${s.stepIndex}. [${s.actionType}] target='${s.target}' payload='${s.payload}' (${s.description})\n")
                    }
                }.trim()

                ToolResult.Success(message = details, data = mapOf("id" to macro.id, "steps" to macro.steps.size))
            }

            "delete" -> {
                if (macroTarget.isBlank()) return ToolResult.Failed("Specify macro name to delete")
                val deleted = macroEngine.deleteMacro(macroTarget)
                if (deleted) {
                    ToolResult.Success("Macro deleted: $macroTarget")
                } else {
                    ToolResult.Failed("Macro not found: $macroTarget")
                }
            }

            else -> ToolResult.Failed("Unknown macro action: '$action'. Supported: list, run, info, delete.")
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Macro action failed: ${result.message}")
        }
        val action = params["action"]?.trim()?.lowercase() ?: "run"
        return when (action) {
            "run", "execute", "trigger" -> {
                val completed = result.data["completed_steps"] as? Int
                val total = result.data["total_steps"] as? Int
                when {
                    completed != null && completed == total && total > 0 -> VerificationResult.success(
                        "Macro completed ($completed/$total steps)",
                        mapOf("completed" to completed.toString(), "total" to total.toString(), "outcome" to "COMPLETED")
                    )
                    completed != null -> VerificationResult.unknown(
                        "Macro progressed $completed/${total ?: "?"} steps; completion not confirmed",
                        mapOf("completed" to completed.toString(), "outcome" to "PARTIAL")
                    )
                    else -> VerificationResult.unknown("Macro run reported without step counts", mapOf("outcome" to "UNPARSED"))
                }
            }
            "list", "info", "get", "delete" ->
                if (result.data.containsKey("count") || result.data.containsKey("id")) {
                    VerificationResult.success("Macro $action confirmed", mapOf("action" to action, "outcome" to "CONFIRMED"))
                } else {
                    VerificationResult.success("Macro $action completed", mapOf("action" to action, "outcome" to "COMPLETED"))
                }
            else -> VerificationResult.unknown("No verifier for macro action '$action'", mapOf("outcome" to "UNKNOWN_ACTION"))
        }
    }
}
