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

/**
 * Native client for Google Gemini REST API (Gemini 2.0 Flash / Gemini 1.5 Pro).
 * Supports text generation, SSE token streaming, and multimodal vision payloads.
 */
class GeminiLlm(
    private val apiKey: String,
    private val model: String = DEFAULT_GEMINI_MODEL,
    private val metrics: MetricsCollector = MetricsCollector.shared
) : LlmClient, java.io.Closeable {

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"
        const val PRO_GEMINI_MODEL = "gemini-1.5-pro"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

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
            component = "gemini_llm",
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
                IllegalArgumentException("Gemini API key is missing. Set GEMINI_API_KEY or configure in API Manager.")
            )
        }

        val url = "$BASE_URL/$model:streamGenerateContent?key=$apiKey&alt=sse"
        val jsonPayload = buildGeminiPayload(messages)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                activeCalls.remove(call)
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Gemini API error ($code): $errorBody"))
                }

                val fullText = StringBuilder()
                val reader = response.body?.charStream()?.buffered()
                    ?: return@withContext Result.failure(IOException("Empty response body from Gemini"))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!currentCoroutineContext().isActive) {
                        call.cancel()
                        throw CancellationException("Gemini chat cancelled")
                    }
                    val l = line?.trim() ?: continue
                    if (l.startsWith("data:")) {
                        val jsonStr = l.substring(5).trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue
                        val token = extractCandidateText(jsonStr)
                        if (token.isNotEmpty()) {
                            fullText.append(token)
                            withContext(Dispatchers.Main) { onToken(token) }
                        }
                    }
                }

                val responseString = fullText.toString()
                if (responseString.isNotBlank()) {
                    Result.success(responseString)
                } else {
                    Result.failure(IOException("Empty output from Gemini"))
                }
            }
        } catch (e: CancellationException) {
            activeCalls.remove(call)
            call.cancel()
            throw e
        } catch (e: Exception) {
            activeCalls.remove(call)
            Result.failure(e)
        }
    }

    override suspend fun chatVision(
        prompt: String,
        base64ImageUrl: String?,
        uiContext: String?,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is missing."))
        }

        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val payload = JSONObject()
        val contents = JSONArray()
        val contentObj = JSONObject().apply { put("role", "user") }
        val parts = JSONArray()

        val enrichedPrompt = if (!uiContext.isNullOrBlank()) "$prompt\n\nUI Context:\n$uiContext" else prompt
        parts.put(JSONObject().apply { put("text", enrichedPrompt) })

        if (!base64ImageUrl.isNullOrBlank()) {
            val cleanBase64 = if (base64ImageUrl.contains(",")) {
                base64ImageUrl.substringAfter(",")
            } else {
                base64ImageUrl
            }
            val inlineData = JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", cleanBase64)
            }
            parts.put(JSONObject().apply { put("inlineData", inlineData) })
        }

        contentObj.put("parts", parts)
        contents.put(contentObj)
        payload.put("contents", contents)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                activeCalls.remove(call)
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Gemini vision error (${response.code}): $body"))
                }
                val text = extractCandidateText(body)
                if (text.isNotBlank()) {
                    withContext(Dispatchers.Main) { onToken(text) }
                    Result.success(text)
                } else {
                    Result.failure(IOException("Empty vision response from Gemini"))
                }
            }
        } catch (e: Exception) {
            activeCalls.remove(call)
            Result.failure(e)
        }
    }

    private fun buildGeminiPayload(messages: List<Message>): JSONObject {
        val payload = JSONObject()
        val contents = JSONArray()

        for (msg in messages) {
            val role = if (msg.role.equals("assistant", ignoreCase = true)) "model" else "user"
            val contentObj = JSONObject()
            contentObj.put("role", role)
            val parts = JSONArray()
            parts.put(JSONObject().apply { put("text", msg.content) })
            contentObj.put("parts", parts)
            contents.put(contentObj)
        }
        payload.put("contents", contents)

        // System prompt generation config
        val genConfig = JSONObject().apply {
            put("temperature", 0.3)
            put("maxOutputTokens", 2048)
        }
        payload.put("generationConfig", genConfig)
        return payload
    }

    private fun extractCandidateText(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val candidates = obj.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    override fun cancel() {
        for (call in activeCalls) {
            try { call.cancel() } catch (_: Exception) {}
        }
        activeCalls.clear()
    }

    override fun close() {
        cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
