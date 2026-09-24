package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSearchTool(private val context: Context) : Tool {
    override val name: String = "SEARCH_WEB"
    override val description: String = "Performs web research and factual lookup, returning extracted candidate results."
    override val policy: ToolPolicy = ToolPolicy(timeoutMs = 60_000L)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.trim()
            ?: return@withContext ToolResult(false, "Search query missing")

        val openBrowser = params["openBrowser"]?.toBoolean() ?: false
        if (openBrowser) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return@withContext ToolResult(true, "Opened web search in browser for '$query'")
        }

        try {
            // 1. Try DuckDuckGo Instant Answer API for structured knowledge
            val apiUrl = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Jarvis Assistant)")
                .build()

            val ddgResult = httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val abstractText = json.optString("AbstractText", "")
                        val heading = json.optString("Heading", "")
                        val relatedTopics = json.optJSONArray("RelatedTopics")

                        val results = mutableListOf<String>()
                        if (abstractText.isNotBlank()) {
                            results.add("$heading: $abstractText")
                        }
                        if (relatedTopics != null) {
                            for (i in 0 until minOf(relatedTopics.length(), 4)) {
                                val topic = relatedTopics.optJSONObject(i)
                                val text = topic?.optString("Text", "").orEmpty()
                                if (text.isNotBlank()) results.add(text)
                            }
                        }
                        results
                    } else null
                } else null
            }

            if (!ddgResult.isNullOrEmpty()) {
                return@withContext ToolResult.Success(
                    message = ddgResult.joinToString("\n"),
                    data = mapOf("query" to query, "count" to ddgResult.size)
                )
            }

            // 2. Fallback: Query Wikipedia Search API
            val wikiUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=${Uri.encode(query)}&limit=4&namespace=0&format=json"
            val wikiReq = Request.Builder().url(wikiUrl).build()
            val wikiResult = httpClient.newCall(wikiReq).execute().use { wikiResp ->
                if (wikiResp.isSuccessful) {
                    val wikiBody = wikiResp.body?.string().orEmpty()
                    if (wikiBody.isNotBlank()) {
                        val arr = org.json.JSONArray(wikiBody)
                        if (arr.length() >= 3) {
                            val titles = arr.optJSONArray(1)
                            val descriptions = arr.optJSONArray(2)
                            val results = mutableListOf<String>()
                            if (titles != null) {
                                for (i in 0 until titles.length()) {
                                    val t = titles.optString(i)
                                    val d = descriptions?.optString(i).orEmpty()
                                    if (t.isNotBlank()) {
                                        results.add(if (d.isNotBlank()) "$t - $d" else t)
                                    }
                                }
                            }
                            results
                        } else null
                    } else null
                } else null
            }

            if (!wikiResult.isNullOrEmpty()) {
                return@withContext ToolResult.Success(
                    message = wikiResult.joinToString("\n"),
                    data = mapOf("query" to query, "count" to wikiResult.size)
                )
            }

            // If no structured result found online:
            ToolResult.Success(
                message = "No direct web encyclopedic match for '$query'. Synthesize and provide the best response using domain knowledge.",
                data = mapOf("query" to query, "count" to 0)
            )
        } catch (e: Exception) {
            Log.w("WebSearchTool", "Web search request failed for '$query'", e)
            ToolResult.Success(
                message = "Web lookup unavailable (${e.message}). Proceed using assistant knowledge.",
                data = mapOf("query" to query, "error" to (e.message ?: "network_error"))
            )
        }
    }
}
