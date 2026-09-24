package com.jarvis.ai

data class Message(
    val role: String,
    val content: String
)

interface LlmClient : java.io.Closeable {
    suspend fun chat(
        messages: List<Message>,
        onToken: (String) -> Unit = {}
    ): Result<String>

    suspend fun chatVision(
        prompt: String,
        base64ImageUrl: String?,
        uiContext: String? = null,
        onToken: (String) -> Unit = {}
    ): Result<String> = chat(
        listOf(Message("user", if (!uiContext.isNullOrBlank()) "$prompt\n\nUI Context:\n$uiContext" else prompt)),
        onToken
    )

    fun cancel()

    override fun close() {}
}
