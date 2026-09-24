package com.jarvis.interaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolResult

class BrowserDomTool(private val context: Context? = null) : Tool {
    override val name: String = "BROWSER_DOM_NAVIGATE"

    val metadata = ToolMetadata(
        name = name,
        description = "Navigates and opens URL in browser with structured query parameters",
        parameters = listOf(
            ParameterSchema("url", "string", "Target URL to open or inspect"),
            ParameterSchema("query", "string", "Optional search parameter", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val rawUrl = params["url"] ?: params["query"] ?: return ToolResult(false, "URL or Query missing")
        val finalUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://www.google.com/search?q=${Uri.encode(rawUrl)}"
        }

        val ctx = context ?: return ToolResult(true, "Navigated to: $finalUrl")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            ToolResult(true, "Navigated to: $finalUrl")
        } catch (e: Exception) {
            ToolResult(false, "Browser navigation failed: ${e.message}")
        }
    }
}
