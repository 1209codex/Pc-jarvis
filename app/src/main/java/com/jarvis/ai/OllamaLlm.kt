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
 * Local / Private LLM client connecting to Ollama instances (Termux on-device, local network, or home server).
 * Endpoints: http://127.0.0.1:11434/api/chat
 */
class OllamaLlm(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val metrics: MetricsCollector = MetricsCollector.shared
) : LlmClient, java.io.Closeable {

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"
        const val DEFAULT_MODEL = "llama3.2:3b"
        const val QWEN_MODEL = "qwen2.5:3b"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
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
            component = "ollama_llm",
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
        val cleanUrl = baseUrl.trimEnd('/') + "/api/chat"
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            val msgsArr = JSONArray()
            for (m in messages) {
                msgsArr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            put("messages", msgsArr)
        }

        val request = Request.Builder()
            .url(cleanUrl)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)

        try {
            call.execute().use { response ->
                activeCalls.remove(call)
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Ollama error ($code): $errorBody"))
                }

                val fullText = StringBuilder()
                val reader = response.body?.charStream()?.buffered()
                    ?: return@withContext Result.failure(IOException("Empty response from Ollama"))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!currentCoroutineContext().isActive) {
                        call.cancel()
                        throw CancellationException("Ollama chat cancelled")
                    }
                    val l = line?.trim() ?: continue
                    if (l.isEmpty()) continue
                    try {
                        val json = JSONObject(l)
                        val msg = json.optJSONObject("message")
                        val token = msg?.optString("content", "").orEmpty()
                        if (token.isNotEmpty()) {
                            fullText.append(token)
                            withContext(Dispatchers.Main) { onToken(token) }
                        }
                    } catch (_: Exception) {}
                }

                val resultStr = fullText.toString()
                if (resultStr.isNotBlank()) Result.success(resultStr)
                else Result.failure(IOException("Empty stream output from Ollama"))
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
        val cleanUrl = baseUrl.trimEnd('/') + "/api/chat"
        val enrichedPrompt = if (!uiContext.isNullOrBlank()) "$prompt\n\nUI Context:\n$uiContext" else prompt
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", false)
            val msgsArr = JSONArray()
            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", enrichedPrompt)
                if (!base64ImageUrl.isNullOrBlank()) {
                    val cleanBase64 = if (base64ImageUrl.contains(",")) base64ImageUrl.substringAfter(",") else base64ImageUrl
                    val imgArr = JSONArray()
                    imgArr.put(cleanBase64)
                    put("images", imgArr)
                }
            }
            msgsArr.put(userMsg)
            put("messages", msgsArr)
        }

        val request = Request.Builder()
            .url(cleanUrl)
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
                    return@withContext Result.failure(IOException("Ollama vision error (${response.code}): $body"))
                }
                val json = JSONObject(body)
                val text = json.optJSONObject("message")?.optString("content", "").orEmpty()
                if (text.isNotBlank()) {
                    withContext(Dispatchers.Main) { onToken(text) }
                    Result.success(text)
                } else {
                    Result.failure(IOException("Empty vision output from Ollama"))
                }
            }
        } catch (e: Exception) {
            activeCalls.remove(call)
            Result.failure(e)
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
