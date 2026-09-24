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
 * Universal client for OpenRouter API (Claude 3.5, DeepSeek R1, Llama 3.3, Mistral, etc.).
 * Standard OpenAI-compatible SSE streaming.
 */
class OpenRouterLlm(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val metrics: MetricsCollector = MetricsCollector.shared
) : LlmClient, java.io.Closeable {

    companion object {
        const val DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct"
        const val CLAUDE_MODEL = "anthropic/claude-3.5-sonnet"
        const val DEEPSEEK_MODEL = "deepseek/deepseek-r1"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
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
            component = "openrouter_llm",
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
                IllegalArgumentException("OpenRouter API key is missing. Configure in API Manager.")
            )
        }

        val jsonPayload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", 0.3)
            val msgsArray = JSONArray()
            for (m in messages) {
                msgsArray.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            put("messages", msgsArray)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/1209codex/android-jarvis")
            .addHeader("X-Title", "J.A.R.V.I.S. Android Super-Agent")
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
                    return@withContext Result.failure(IOException("OpenRouter error ($code): $errorBody"))
                }

                val fullText = StringBuilder()
                val reader = response.body?.charStream()?.buffered()
                    ?: return@withContext Result.failure(IOException("Empty response body from OpenRouter"))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!currentCoroutineContext().isActive) {
                        call.cancel()
                        throw CancellationException("OpenRouter chat cancelled")
                    }
                    val l = line?.trim() ?: continue
                    if (l.startsWith("data:")) {
                        val jsonStr = l.substring(5).trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue
                        val token = extractDeltaToken(jsonStr)
                        if (token.isNotEmpty()) {
                            fullText.append(token)
                            withContext(Dispatchers.Main) { onToken(token) }
                        }
                    }
                }

                val resultStr = fullText.toString()
                if (resultStr.isNotBlank()) Result.success(resultStr)
                else Result.failure(IOException("Empty output from OpenRouter"))
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
            return@withContext Result.failure(IllegalArgumentException("OpenRouter API key missing"))
        }

        val enrichedPrompt = if (!uiContext.isNullOrBlank()) "$prompt\n\nUI Context:\n$uiContext" else prompt
        val jsonPayload = JSONObject().apply {
            put("model", model)
            val msgsArray = JSONArray()
            val userMsg = JSONObject().apply {
                put("role", "user")
                if (!base64ImageUrl.isNullOrBlank()) {
                    val contentArr = JSONArray()
                    contentArr.put(JSONObject().apply {
                        put("type", "text")
                        put("text", enrichedPrompt)
                    })
                    val formattedImg = if (base64ImageUrl.startsWith("data:")) base64ImageUrl else "data:image/jpeg;base64,$base64ImageUrl"
                    contentArr.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", formattedImg) })
                    })
                    put("content", contentArr)
                } else {
                    put("content", enrichedPrompt)
                }
            }
            msgsArray.put(userMsg)
            put("messages", msgsArray)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                activeCalls.remove(call)
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("OpenRouter vision error (${response.code}): $body"))
                }
                val text = extractChoiceContent(body)
                if (text.isNotBlank()) {
                    withContext(Dispatchers.Main) { onToken(text) }
                    Result.success(text)
                } else {
                    Result.failure(IOException("Empty vision output from OpenRouter"))
                }
            }
        } catch (e: Exception) {
            activeCalls.remove(call)
            Result.failure(e)
        }
    }

    private fun extractDeltaToken(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val firstChoice = choices.getJSONObject(0)
            val delta = firstChoice.optJSONObject("delta") ?: return ""
            delta.optString("content", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractChoiceContent(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message") ?: return ""
            message.optString("content", "")
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
