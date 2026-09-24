package com.jarvis.tools

import android.content.Context
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class FlashlightTool(private val context: Context? = null) : Tool {
    override val name: String = "FLASHLIGHT"
    override val description: String =
        "Turns the device flashlight/torch on or off. Parameter 'mode' must be \"on\" to switch it on and \"off\" to switch it off."

    val metadata = ToolMetadata(
        name = name,
        description = description,
        parameters = listOf(ParameterSchema("mode", "string", "on or off", required = true)),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val raw = params["mode"]?.trim()?.lowercase()
        val on = when (raw) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> return ToolResult.Failed("Specify mode=on or mode=off")
        }
        val ctx = context ?: return ToolResult(true, "Flashlight switched to $raw (test mode)")
        return SystemSwitchboardTool(ctx).execute(mapOf("action" to "led", "target" to if (on) "on" else "off"))
    }
}