package com.jarvis.tools

import android.content.Context
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class ListInstalledAppsTool(private val context: Context? = null) : Tool {
    override val name: String = "APPS_LIST"
    override val description: String =
        "Scans and lists installed applications on the Android device. Parameters: action ('list', 'scan', 'search', 'count'), query (optional search keyword for app name)."

    override val policy: ToolPolicy = ToolPolicy(
        idempotent = true,
        retryable = true,
        riskLevel = RiskLevel.LOW
    )

    val metadata = ToolMetadata(
        name = "APPS_LIST",
        description = "Scans, counts, and searches installed Android applications on the device.",
        parameters = listOf(
            com.jarvis.foundation.ParameterSchema("action", "string", "Action to perform: list, scan, search, count", required = false),
            com.jarvis.foundation.ParameterSchema("query", "string", "Search query or app name keyword", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Success("App scanner simulation: 50+ apps available.")
        val action = params["action"]?.trim()?.lowercase() ?: if (params.containsKey("query")) "search" else "list"
        val query = params["query"]?.trim().orEmpty()

        return when (action) {
            "search" -> {
                if (query.isBlank()) {
                    return ToolResult.Failed("Search query parameter is required to search installed apps.")
                }
                val matches = AppScanner.searchApps(ctx, query)
                if (matches.isEmpty()) {
                    ToolResult.Success(
                        message = "No installed apps found matching '$query'.",
                        data = mapOf("query" to query, "count" to 0, "results" to emptyList<String>())
                    )
                } else {
                    val appNames = matches.joinToString(", ") { "${it.label} (${it.packageName})" }
                    ToolResult.Success(
                        message = "Found ${matches.size} app(s) matching '$query': $appNames",
                        data = mapOf(
                            "query" to query,
                            "count" to matches.size,
                            "results" to matches.map { mapOf("label" to it.label, "package" to it.packageName) }
                        )
                    )
                }
            }

            "count" -> {
                val count = AppScanner.getInstalledAppsCount(ctx)
                ToolResult.Success(
                    message = "There are $count launchable applications installed on this device.",
                    data = mapOf("count" to count)
                )
            }

            "scan", "refresh" -> {
                val apps = AppScanner.refresh(ctx)
                val sample = apps.take(15).joinToString(", ") { it.label }
                ToolResult.Success(
                    message = "Scan complete. Discovered ${apps.size} installed applications. Top apps include: $sample...",
                    data = mapOf(
                        "total_count" to apps.size,
                        "apps" to apps.map { mapOf("label" to it.label, "package" to it.packageName) }
                    )
                )
            }

            "list" -> {
                val apps = AppScanner.scanInstalledApps(ctx)
                val sample = apps.take(15).joinToString(", ") { it.label }
                ToolResult.Success(
                    message = "You have ${apps.size} installed applications on this device including: $sample...",
                    data = mapOf(
                        "total_count" to apps.size,
                        "apps" to apps.map { mapOf("label" to it.label, "package" to it.packageName) }
                    )
                )
            }

            else -> ToolResult.Failed("Unknown action '$action'. Available actions: list, scan, search, count.")
        }
    }
}
