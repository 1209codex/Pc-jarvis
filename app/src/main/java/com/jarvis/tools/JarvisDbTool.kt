package com.jarvis.tools

import android.content.Context
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.storage.JarvisStorageHub
import com.jarvis.storage.VectorDbManager
import java.io.File

class JarvisDbTool(
    private val context: Context? = null,
    private val vectorDbManager: VectorDbManager? = null
) : Tool {

    override val name: String = "JARVIS_DB"
    override val description: String =
        "Inspects and queries the central local JARVIS storage hub at /storage/emulated/0/jarvis-db. Actions: 'stats' (shows database, vector-db and storage stats), 'manifest' (reads manifest.json), 'vector_search' (searches vector-db), 'index_vector' (stores text into vector-db)."

    override val policy: ToolPolicy = ToolPolicy(
        idempotent = true,
        retryable = true,
        riskLevel = RiskLevel.LOW
    )

    val metadata = ToolMetadata(
        name = "JARVIS_DB",
        description = "Provides status, metrics, and vector search over the central /storage/emulated/0/jarvis-db directory.",
        parameters = listOf(
            com.jarvis.foundation.ParameterSchema("action", "string", "Action: stats, manifest, vector_search, index_vector", required = false),
            com.jarvis.foundation.ParameterSchema("query", "string", "Query or text for vector database operations", required = false),
            com.jarvis.foundation.ParameterSchema("topic", "string", "Topic category for vector indexing", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult.Success("Jarvis-DB simulation mode.")
        val rawAction = params["action"]?.trim()?.lowercase()
        val action = when {
            rawAction != null -> rawAction
            params.containsKey("vector_search") || params.containsKey("search") -> "vector_search"
            params.containsKey("index") || params.containsKey("index_vector") || params.containsKey("store") -> "index_vector"
            else -> "stats"
        }
        val query = params["query"]?.trim()
            ?: params["text"]?.trim()
            ?: params["content"]?.trim()
            ?: params["search"]?.trim()
            ?: params["q"]?.trim()
            ?: params["data"]?.trim().orEmpty()
        val topic = params["topic"]?.trim()
            ?: params["category"]?.trim()
            ?: params["tag"]?.trim().orEmpty().ifBlank { "general" }

        val vdb = vectorDbManager ?: VectorDbManager(ctx)

        return when (action) {
            "stats", "status" -> {
                JarvisStorageHub.initStorage(ctx)
                val overview = JarvisStorageHub.getStorageOverview(ctx)
                val vStats = vdb.getStats()
                val manifestFile = JarvisStorageHub.updateManifest(ctx)

                val dbDir = JarvisStorageHub.getDatabasesDirectory(ctx)
                val dbList = dbDir.listFiles()?.filter { it.name.endsWith(".db") }?.joinToString(", ") { it.name } ?: "none"

                val message = """
                    JARVIS Central Storage Hub Status:
                    • Location: ${overview["root_path"]}
                    • Total Databases: ${overview["total_databases"]} ($dbList)
                    • Vector DB: ${vStats.totalVectors} embeddings indexed (${vStats.dimensions}-dimensional)
                    • Total Reports: ${overview["total_reports"]}
                    • Hub Storage Used: ${overview["formatted_size"]}
                    • Manifest: ${manifestFile.name} (Updated)
                """.trimIndent()

                ToolResult.Success(
                    message = message,
                    data = mapOf(
                        "root_path" to overview["root_path"].toString(),
                        "databases" to dbList,
                        "vector_count" to vStats.totalVectors,
                        "reports_count" to overview["total_reports"].toString(),
                        "manifest_path" to manifestFile.absolutePath
                    )
                )
            }

            "manifest" -> {
                val root = JarvisStorageHub.getRootDirectory(ctx)
                val manifest = File(root, "jarvis_manifest.json")
                if (manifest.exists()) {
                    ToolResult.Success(manifest.readText(Charsets.UTF_8))
                } else {
                    JarvisStorageHub.initStorage(ctx)
                    ToolResult.Success("Manifest generated: ${JarvisStorageHub.updateManifest(ctx).absolutePath}")
                }
            }

            "vector_search", "search_vectors" -> {
                if (query.isBlank()) {
                    return ToolResult.Failed("Search query parameter is required for vector search.")
                }
                val matches = vdb.searchSimilar(query, topK = 5)
                if (matches.isEmpty()) {
                    ToolResult.Success("No closely matching vectors found in vector-db for '$query'.")
                } else {
                    val formatted = matches.joinToString("\n") { m ->
                        val scorePct = (m.score * 100).toInt()
                        "• [$scorePct%] (${m.topic}) ${m.text.take(120)}"
                    }
                    ToolResult.Success("Found ${matches.size} vector match(es) for '$query':\n$formatted")
                }
            }

            "index_vector", "store_vector" -> {
                if (query.isBlank()) {
                    return ToolResult.Failed("Text content in 'query' parameter is required to index into vector-db.")
                }
                val id = "vec_" + System.currentTimeMillis()
                val success = vdb.storeVector(id = id, text = query, topic = topic)
                if (success) {
                    ToolResult.Success("Stored entry into Vector DB under topic '$topic' with id $id.")
                } else {
                    ToolResult.Failed("Failed to store entry into Vector DB.")
                }
            }

            else -> ToolResult.Failed("Unknown action '$action'. Available actions: stats, manifest, vector_search, index_vector.")
        }
    }
}
