package com.jarvis.ai

import com.jarvis.foundation.MetricsCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GroqLlm(
    private val apiKey: String,
    private val model: String = LlmConfig.DEFAULT_MODEL,
    private val baseUrl: String = LlmConfig.DEFAULT_BASE_URL,
    private val metrics: MetricsCollector = MetricsCollector.shared
) : LlmClient, java.io.Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    override suspend fun chat(
        messages: List<Message>,
        onToken: (String) -> Unit
    ): Result<String> {
        val start = System.currentTimeMillis()
        val result = chatInternal(messages, onToken)
        metrics.record(
            component = "llm",
            operation = "chat",
            ok = result.isSuccess,
            wallMs = System.currentTimeMillis() - start,
            tokensIn = messages.sumOf { MetricsCollector.estimateTokens(it.content) },
            tokensOut = MetricsCollector.estimateTokens(result.getOrNull().orEmpty())
        )
        return result
    }

    private suspend fun chatInternal(
        messages: List<Message>,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Groq API key is missing. Set GROQ_API_KEY in local.properties.")
            )
        }

        val candidateModels = listOf(model) + LlmConfig.CANDIDATE_MODELS
        val distinctCandidates = candidateModels.filter { it.isNotBlank() }.distinct()
        var lastError: Exception? = null

        for (activeModel in distinctCandidates) {
            // API requires at least one message to contain the word 'json' when using json_object response_format
            val messagesForApi = messages.map { mapOf("role" to it.role, "content" to it.content) }.toMutableList()
            val hasJsonWord = messagesForApi.any { it["content"]?.contains("json", ignoreCase = true) == true }
            if (!hasJsonWord && messagesForApi.isNotEmpty()) {
                val last = messagesForApi.last().toMutableMap()
                last["content"] = "${last["content"]} Respond using JSON."
                messagesForApi[messagesForApi.lastIndex] = last
            }
            val bodyJson = JSONObject().apply {
                put("model", activeModel)
                put("messages", JSONArray().apply {
                    for (m in messagesForApi) {
                        put(JSONObject(m))
                    }
                })
                put("stream", true)
                put("temperature", 0.3)
                put("response_format", JSONObject().put("type", "json_object"))
            }.toString()
            val fullUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl else "${baseUrl.trimEnd('/')}/chat/completions"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = client.newCall(request)
            activeCalls.add(call)

            try {
                val res = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        val errorBody = response.body?.string() ?: ""
                        android.util.Log.w("GroqLlm", "Model $activeModel returned HTTP $code: $errorBody")
                        lastError = IOException("Groq request failed for model $activeModel with HTTP $code: $errorBody")
                        return@use null
                    }
                    val bodyStream = response.body
                    if (bodyStream == null) {
                        lastError = IOException("Groq model $activeModel returned empty body")
                        return@use null
                    }
                    val fullResponse = StringBuilder()

                    bodyStream.byteStream().bufferedReader().useLines { lines ->
                        for (rawLine in lines) {
                            if (!currentCoroutineContext().isActive) {
                                call.cancel()
                                throw CancellationException("Groq request cancelled")
                            }
                            val line = rawLine.trim()
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty() || payload == "[DONE]") continue

                            try {
                                val chunk = JSONObject(payload)
                                val choices = chunk.optJSONArray("choices")
                                val first = choices?.optJSONObject(0)
                                val delta = first?.optJSONObject("delta")
                                val content = delta?.optString("content")
                                if (!content.isNullOrEmpty()) {
                                    fullResponse.append(content)
                                    onToken(content)
                                }
                            } catch (_: Exception) {
                                // Skip malformed SSE chunks
                            }
                        }
                    }
                    fullResponse.toString()
                }

                if (res != null) {
                    return@withContext Result.success(res)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                activeCalls.remove(call)
            }
        }

        Result.failure(lastError ?: IOException("All candidate Groq models failed."))
    }

    private fun isModelUnavailable(code: Int, errorBody: String): Boolean {
        val normalized = errorBody.lowercase()
        return code == 404 ||
                normalized.contains("model_not_found") ||
                normalized.contains("model_decommissioned") ||
                normalized.contains("model_deprecated") ||
                normalized.contains("decommissioned")
    }

    override suspend fun chatVision(
        prompt: String,
        base64ImageUrl: String?,
        uiContext: String?,
        onToken: (String) -> Unit
    ): Result<String> {
        val start = System.currentTimeMillis()
        val result = chatVisionInternal(prompt, base64ImageUrl, uiContext, onToken)
        metrics.record(
            component = "llm",
            operation = "chat_vision",
            ok = result.isSuccess,
            wallMs = System.currentTimeMillis() - start,
            tokensIn = MetricsCollector.estimateTokens(prompt + (uiContext ?: "")),
            tokensOut = MetricsCollector.estimateTokens(result.getOrNull().orEmpty())
        )
        return result
    }

    private suspend fun chatVisionInternal(
        prompt: String,
        base64ImageUrl: String?,
        uiContext: String?,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Groq API key is missing. Set GROQ_API_KEY in local.properties.")
            )
        }

        val fullPrompt = buildString {
            appendLine(prompt)
            if (!uiContext.isNullOrBlank()) {
                appendLine()
                appendLine("Accessibility UI Context:")
                appendLine(uiContext)
            }
        }

        if (base64ImageUrl.isNullOrBlank()) {
            return@withContext chat(
                listOf(Message("user", fullPrompt)),
                onToken
            )
        }

        val contentList = listOf(
            mapOf("type" to "text", "text" to fullPrompt),
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to base64ImageUrl)
            )
        )

        val messagesPayload = listOf(
            mapOf("role" to "user", "content" to contentList)
        )

        val candidateModels = LlmConfig.VISION_CANDIDATE_MODELS
        var lastError: Exception? = null

        for (activeModel in candidateModels) {
            val bodyJson = JSONObject().apply {
                put("model", activeModel)
                put("messages", JSONArray().apply {
                    for (m in messagesPayload) {
                        put(JSONObject(m))
                    }
                })
                put("temperature", 0.2)
                put("max_tokens", 1024)
            }.toString()
            val fullUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl else "${baseUrl.trimEnd('/')}/chat/completions"
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = client.newCall(request)
            activeCalls.add(call)

            try {
                val res = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        val errorBody = response.body?.string() ?: ""
                        android.util.Log.w("GroqLlm", "Vision model $activeModel returned HTTP $code: $errorBody")
                        lastError = IOException("Groq vision request failed for model $activeModel with HTTP $code: $errorBody")
                        return@use null
                    }
                    val jsonStr = response.body?.string().orEmpty()
                    val jsonObj = org.json.JSONObject(jsonStr)
                    val choices = jsonObj.optJSONArray("choices")
                    val message = choices?.optJSONObject(0)?.optJSONObject("message")
                    message?.optString("content", "")
                }
                if (!res.isNullOrBlank()) {
                    onToken(res)
                    return@withContext Result.success(res)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("GroqLlm", "Vision call threw for $activeModel: ${e.message}")
                lastError = e
            } finally {
                activeCalls.remove(call)
            }
        }

        android.util.Log.i("GroqLlm", "Vision models failed (${lastError?.message}), falling back to text chat with UI tree")
        chat(listOf(Message("user", fullPrompt)), onToken)
    }

    override fun cancel() {
        val calls = activeCalls.toList()
        activeCalls.clear()
        calls.forEach { call ->
            try { call.cancel() } catch (_: Throwable) {}
        }
    }

    override fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        try { client.cache?.close() } catch (_: Throwable) {}
    }

    companion object {
        const val DEFAULT_BASE_URL = LlmConfig.DEFAULT_BASE_URL
        const val DEFAULT_MODEL = LlmConfig.DEFAULT_MODEL
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
