package com.jarvis

import com.jarvis.ai.LlmClient
import com.jarvis.ai.Message
import com.jarvis.ai.MultiProviderLlmClient
import com.jarvis.ai.ProviderEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class MultiProviderLlmClientTest {

    private class FakeLlmClient(
        val name: String,
        val shouldFail: Boolean = false,
        val responseText: String = "Response from $name"
    ) : LlmClient, Closeable {
        var closed = false
        var cancelled = false
        var chatCalls = 0

        override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
            chatCalls++
            return if (shouldFail) {
                Result.failure(RuntimeException("$name failed"))
            } else {
                onToken(responseText)
                Result.success(responseText)
            }
        }

        override suspend fun chatVision(
            prompt: String,
            base64ImageUrl: String?,
            uiContext: String?,
            onToken: (String) -> Unit
        ): Result<String> {
            return if (shouldFail) {
                Result.failure(RuntimeException("$name vision failed"))
            } else {
                Result.success("Vision response from $name")
            }
        }

        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun testPrimaryProviderSucceeds() = runBlocking {
        val primary = FakeLlmClient("Groq")
        val fallback = FakeLlmClient("Gemini")

        val client = MultiProviderLlmClient(
            listOf(
                ProviderEntry("Groq", primary, priority = 0),
                ProviderEntry("Gemini", fallback, priority = 1)
            )
        )

        val result = client.chat(listOf(Message("user", "Hello")), onToken = {})
        assertTrue(result.isSuccess)
        assertEquals("Response from Groq", result.getOrNull())
        assertEquals(1, primary.chatCalls)
        assertEquals(0, fallback.chatCalls)
    }

    @Test
    fun testFailoverToSecondaryWhenPrimaryFails() = runBlocking {
        val primaryFailing = FakeLlmClient("Groq", shouldFail = true)
        val fallbackSecondary = FakeLlmClient("Gemini", shouldFail = false)

        val client = MultiProviderLlmClient(
            listOf(
                ProviderEntry("Groq", primaryFailing, priority = 0),
                ProviderEntry("Gemini", fallbackSecondary, priority = 1)
            )
        )

        val result = client.chat(listOf(Message("user", "Analyze code")), onToken = {})
        assertTrue(result.isSuccess)
        assertEquals("Response from Gemini", result.getOrNull())
        assertEquals(1, primaryFailing.chatCalls)
        assertEquals(1, fallbackSecondary.chatCalls)
    }

    @Test
    fun testAllProvidersFailReturnsStructuredError() = runBlocking {
        val client = MultiProviderLlmClient(
            listOf(
                ProviderEntry("Groq", FakeLlmClient("Groq", shouldFail = true), priority = 0),
                ProviderEntry("Gemini", FakeLlmClient("Gemini", shouldFail = true), priority = 1)
            )
        )

        val result = client.chat(listOf(Message("user", "Test")), onToken = {})
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("All LLM providers failed"))
        assertTrue(msg.contains("Groq failed"))
        assertTrue(msg.contains("Gemini failed"))
    }

    @Test
    fun testVisionFailover() = runBlocking {
        val failingGroq = FakeLlmClient("Groq", shouldFail = true)
        val succeedingGemini = FakeLlmClient("Gemini", shouldFail = false)

        val client = MultiProviderLlmClient(
            listOf(
                ProviderEntry("Groq", failingGroq, priority = 0),
                ProviderEntry("Gemini", succeedingGemini, priority = 1)
            )
        )

        val result = client.chatVision("Inspect screenshot", null)
        assertTrue(result.isSuccess)
        assertEquals("Vision response from Gemini", result.getOrNull())
    }

    @Test
    fun testClosePropagatesToAllProviders() {
        val p1 = FakeLlmClient("Groq")
        val p2 = FakeLlmClient("Gemini")

        val client = MultiProviderLlmClient(
            listOf(
                ProviderEntry("Groq", p1, priority = 0),
                ProviderEntry("Gemini", p2, priority = 1)
            )
        )

        client.close()
        assertTrue(p1.closed)
        assertTrue(p2.closed)
        assertTrue(p1.cancelled)
        assertTrue(p2.cancelled)
    }
}
