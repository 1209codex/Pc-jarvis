package com.jarvis.tools

import android.content.Context
import com.jarvis.device.BatteryMonitor
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class BatteryStatusTool(private val context: Context? = null) : Tool {
    override val name: String = "BATTERY_CHECK"
    override val description: String = "Checks current battery level, percentage, and charging state."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "BATTERY_CHECK",
        description = "Provides battery status and charging connection info.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val state = BatteryMonitor.getBatteryState(context)
        val autopilot = com.jarvis.battery.BatteryThermalAutopilotEngine.instance?.getAutopilotStatus()

        val summary = buildString {
            append(BatteryMonitor.formatSummary(state))
            if (autopilot != null) {
                if (autopilot.estimatedMinutesRemaining != null) {
                    val verb = if (state.isCharging) "until full" else "remaining"
                    append(" Approximately ${autopilot.estimatedMinutesRemaining} mins $verb.")
                }
                append(" Temperature is ${autopilot.temperatureC.toInt()}°C (${autopilot.thermalStatus}).")
            }
        }

        return ToolResult.Success(
            message = summary,
            data = mapOf(
                "percentage" to state.percentage,
                "is_charging" to state.isCharging,
                "is_full" to state.isFull,
                "plugged" to state.pluggedSource,
                "temperature" to state.temperatureC,
                "estimated_minutes" to (autopilot?.estimatedMinutesRemaining ?: -1),
                "thermal_status" to (autopilot?.thermalStatus ?: "NORMAL")
            )
        )
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        val pct = result.data["percentage"] as? Int
        return if (!result.success) {
            VerificationResult.failure("Battery read failed: ${result.message}")
        } else if (pct != null) {
            VerificationResult.success("Battery read back at $pct%", mapOf("percentage" to pct.toString(), "outcome" to "READBACK"))
        } else {
            VerificationResult.unknown("Battery read returned without a percentage", mapOf("outcome" to "UNPARSED"))
        }
    }
}
