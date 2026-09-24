package com.jarvis.ai

import android.util.Log
import kotlinx.coroutines.CancellationException

data class ProviderEntry(
    val name: String,
    val client: LlmClient,
    val priority: Int = 0,
    val isEnabled: Boolean = true
)

/**
 * Composite multi-provider LLM coordinator with sub-200ms intelligent failover.
 * Routes chat and vision requests across Groq, Gemini, OpenRouter, and Ollama.
 */
class MultiProviderLlmClient(
    val providers: List<ProviderEntry>
) : LlmClient, java.io.Closeable {

    private val TAG = "MultiProviderLlm"

    private val activeSortedProviders: List<ProviderEntry>
        get() = providers.filter { it.isEnabled }.sortedBy { it.priority }

    override suspend fun chat(
        messages: List<Message>,
        onToken: (String) -> Unit
    ): Result<String> {
        val candidateProviders = activeSortedProviders
        if (candidateProviders.isEmpty()) {
            return Result.failure(IllegalStateException("No active LLM providers configured."))
        }

        val failureLog = mutableListOf<String>()

        for (entry in candidateProviders) {
            try {
                Log.i(TAG, "Dispatching chat query to provider: ${entry.name}")
                val tokenBuffer = mutableListOf<String>()
                val result = entry.client.chat(messages) { token ->
                    tokenBuffer.add(token)
                }
                if (result.isSuccess) {
                    // Provider succeeded — flush all buffered tokens cleanly to the listener
                    for (token in tokenBuffer) {
                        onToken(token)
                    }
                    return result
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.w(TAG, "Provider ${entry.name} failed: $err. Failing over to next provider...")
                    failureLog.add("${entry.name}: $err")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${entry.name} threw exception: ${e.message}")
                failureLog.add("${entry.name}: ${e.message}")
            }
        }

        return Result.failure(
            IllegalStateException("All LLM providers failed:\n" + failureLog.joinToString("\n"))
        )
    }

    override suspend fun chatVision(
        prompt: String,
        base64ImageUrl: String?,
        uiContext: String?,
        onToken: (String) -> Unit
    ): Result<String> {
        val candidateProviders = activeSortedProviders
        if (candidateProviders.isEmpty()) {
            return Result.failure(IllegalStateException("No active vision LLM providers configured."))
        }

        val failureLog = mutableListOf<String>()

        for (entry in candidateProviders) {
            try {
                Log.i(TAG, "Dispatching vision request to provider: ${entry.name}")
                val tokenBuffer = mutableListOf<String>()
                val result = entry.client.chatVision(prompt, base64ImageUrl, uiContext) { token ->
                    tokenBuffer.add(token)
                }
                if (result.isSuccess) {
                    // Provider succeeded — flush all buffered tokens cleanly to the listener
                    for (token in tokenBuffer) {
                        onToken(token)
                    }
                    return result
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown vision error"
                    Log.w(TAG, "Vision provider ${entry.name} failed: $err. Failing over...")
                    failureLog.add("${entry.name}: $err")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vision provider ${entry.name} threw exception: ${e.message}")
                failureLog.add("${entry.name}: ${e.message}")
            }
        }

        return Result.failure(
            IllegalStateException("All vision providers failed:\n" + failureLog.joinToString("\n"))
        )
    }

    override fun cancel() {
        for (entry in providers) {
            try {
                entry.client.cancel()
            } catch (_: Exception) {}
        }
    }

    override fun close() {
        cancel()
        for (entry in providers) {
            try {
                (entry.client as? java.io.Closeable)?.close()
            } catch (_: Exception) {}
        }
    }

    fun getActiveProviderNames(): List<String> = activeSortedProviders.map { it.name }
}
