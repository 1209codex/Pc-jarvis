package com.jarvis.tools

import com.jarvis.execution.VerificationResult
import com.jarvis.logs.LogReaderEngine

class LogsTool(
    private val logReader: LogReaderEngine = LogReaderEngine.instance ?: LogReaderEngine()
) : Tool {
    override val name: String = "LOGS_READ"
    override val description: String = "Reads and analyzes live device logcat and agent telemetry logs. Parameters: lines (max count, default 20), filter (optional keyword e.g. 'error', 'voice', 'tool'), mode ('summary' or 'raw')."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val linesCount = params["lines"]?.toIntOrNull()?.coerceIn(5, 50) ?: 20
        val filter = params["filter"]?.trim()?.ifBlank { null }
        val mode = params["mode"]?.trim()?.lowercase() ?: "summary"

        val engine = LogReaderEngine.instance ?: logReader
        val logLines = engine.readLogcat(maxLines = linesCount, filter = filter)
        val summary = engine.getFormattedSummary(maxLines = linesCount, filter = filter)

        val fullText = if (mode == "raw") {
            logLines.joinToString("\n")
        } else {
            summary + "\n\n" + logLines.takeLast(10).joinToString("\n")
        }

        return ToolResult.Success(
            message = if (mode == "raw") logLines.joinToString("\n") else summary,
            data = mapOf(
                "summary" to summary,
                "linesCount" to logLines.size,
                "logs" to logLines,
                "fullText" to fullText
            )
        )
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Log read failed: ${result.message}")
        }
        val count = result.data["linesCount"] as? Int
        return if (count != null && count >= 0) {
            VerificationResult.success("$count log lines read", mapOf("count" to count.toString(), "outcome" to "READ"))
        } else {
            VerificationResult.unknown("Logs returned without a line count", mapOf("outcome" to "UNPARSED"))
        }
    }
}
