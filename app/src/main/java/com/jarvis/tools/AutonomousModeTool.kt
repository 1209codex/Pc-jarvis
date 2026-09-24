package com.jarvis.tools

import com.jarvis.autonomous.AmbientContextEngine
import com.jarvis.autonomous.AmbientState
import com.jarvis.autonomous.AutonomousDaemon
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class AutonomousModeTool(
    private val ambientEngine: AmbientContextEngine,
    private val autonomousDaemon: AutonomousDaemon
) : Tool {

    override val name: String = "AUTONOMOUS_CONTROL"
    override val description: String =
        "Controls autonomous autopilot, sets ambient environmental modes (driving, meeting, focus, night, workout), and views proactive decisions. Actions: enable_autopilot, disable_autopilot, set_ambient_mode, status, recent_actions, trigger_tick. Parameters: action, mode."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "AUTONOMOUS_CONTROL",
        description = "Manages JARVIS autonomous autopilot and ambient situation awareness.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "status"
        val rawMode = params["mode"]?.trim()?.lowercase() ?: ""

        when (action) {
            "enable_autopilot", "enable", "on", "start" -> {
                autonomousDaemon.setAutopilotEnabled(true)
                val snapshot = ambientEngine.inferCurrentContext()
                return ToolResult.Success(
                    message = "Autopilot engaged. JARVIS is now operating in autonomous mode (${snapshot.state.displayName}).",
                    data = mapOf("autopilot" to true, "ambient_state" to snapshot.state.name)
                )
            }

            "disable_autopilot", "disable", "off", "stop" -> {
                autonomousDaemon.setAutopilotEnabled(false)
                return ToolResult.Success(
                    message = "Autopilot disengaged. JARVIS will operate only in manual response mode.",
                    data = mapOf("autopilot" to false)
                )
            }

            "set_ambient_mode", "set_mode", "mode" -> {
                val targetState = when (rawMode) {
                    "driving", "drive", "car" -> AmbientState.DRIVING
                    "meeting", "in_meeting" -> AmbientState.MEETING
                    "focus", "work", "deep_work" -> AmbientState.FOCUS
                    "night", "sleep", "wind_down" -> AmbientState.NIGHT_WIND_DOWN
                    "workout", "gym" -> AmbientState.WORKOUT
                    "standby", "normal", "auto", "reset" -> null
                    else -> return ToolResult.Failed("Unknown ambient mode '$rawMode'. Supported: driving, meeting, focus, night, workout, auto.")
                }

                ambientEngine.setManualState(targetState)
                val current = ambientEngine.inferCurrentContext()
                val label = if (targetState != null) "Manual ${targetState.displayName} activated" else "Switched back to Automatic Ambient Detection"
                return ToolResult.Success(
                    message = "$label (Current: ${current.state.displayName}).",
                    data = mapOf("ambient_state" to current.state.name, "is_manual" to current.isManualOverride)
                )
            }

            "status" -> {
                val snapshot = ambientEngine.inferCurrentContext()
                val isAutopilot = autonomousDaemon.isAutopilotEnabled()
                val recent = autonomousDaemon.getRecentActions(limit = 1)
                val lastAction = recent.firstOrNull()?.description ?: "No proactive actions recorded yet"

                val summary = "Autonomous Core Status:\n" +
                        "• Autopilot: ${if (isAutopilot) "ACTIVE" else "STANDBY"}\n" +
                        "• Ambient Context: ${snapshot.state.displayName} (${if (snapshot.isManualOverride) "Manual" else "Sensor Inferred"})\n" +
                        "• Battery Status: ${snapshot.batteryPercent}% (${if (snapshot.isCharging) "Charging" else "On Battery"})\n" +
                        "• Latest Decision: $lastAction"

                return ToolResult.Success(
                    message = summary,
                    data = mapOf("autopilot" to isAutopilot, "ambient_state" to snapshot.state.name)
                )
            }

            "recent_actions", "history", "log" -> {
                val actions = autonomousDaemon.getRecentActions(limit = 6)
                if (actions.isEmpty()) {
                    return ToolResult.Success(
                        message = "No proactive autonomous decisions recorded yet.",
                        data = mapOf("count" to 0)
                    )
                }
                val formatted = actions.joinToString("\n") { "• [${it.triggerType}] ${it.description}" }
                return ToolResult.Success(
                    message = "Recent Autonomous Actions (${actions.size}):\n$formatted",
                    data = mapOf("count" to actions.size)
                )
            }

            "trigger_tick", "tick" -> {
                val actionRecord = autonomousDaemon.evaluateAndExecuteTick()
                val msg = if (actionRecord != null) {
                    if (actionRecord.executed) {
                        "Autopilot performed proactive rule: ${actionRecord.description}"
                    } else {
                        "Autopilot planned proactive rule (not executed on device): ${actionRecord.description}"
                    }
                } else {
                    val snapshot = ambientEngine.inferCurrentContext()
                    "Autonomous tick evaluated. All systems normal in ${snapshot.state.displayName}."
                }
                return ToolResult.Success(
                    message = msg,
                    data = mapOf(
                        "action_taken" to (actionRecord?.executed == true),
                        "verified" to (actionRecord?.verified == true)
                    )
                )
            }

            else -> return ToolResult.Failed("Unknown autonomous control action '$action'. Supported: enable_autopilot, disable_autopilot, set_ambient_mode, status, recent_actions, trigger_tick.")
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Autonomous control action failed: ${result.message}")
        }
        val action = params["action"]?.trim()?.lowercase() ?: "status"
        return when (action) {
            "enable_autopilot", "enable", "on", "start" ->
                if (result.data["autopilot"] == true) VerificationResult.success("Autopilot active", mapOf("outcome" to "ENABLED"))
                else VerificationResult.unknown("Autopilot enable reported without confirmation", mapOf("outcome" to "UNCONFIRMED"))

            "disable_autopilot", "disable", "off", "stop" ->
                if (result.data["autopilot"] == false) VerificationResult.success("Autopilot disengaged", mapOf("outcome" to "DISABLED"))
                else VerificationResult.unknown("Autopilot disable reported without confirmation", mapOf("outcome" to "UNCONFIRMED"))

            "set_ambient_mode", "set_mode", "mode" ->
                if (result.data.containsKey("ambient_state")) VerificationResult.success(
                    "Ambient mode set to '${result.data["ambient_state"]}'",
                    mapOf("outcome" to "MODE_SET")
                )
                else VerificationResult.unknown("Mode set reported without current state", mapOf("outcome" to "UNCONFIRMED"))

            "status", "recent_actions", "history", "log", "trigger_tick", "tick" ->
                VerificationResult.success("Autonomous $action report generated", mapOf("outcome" to "REPORT_GENERATED"))

            else -> VerificationResult.unknown("No verifier for autonomous action '$action'", mapOf("outcome" to "UNKNOWN_ACTION"))
        }
    }
}
