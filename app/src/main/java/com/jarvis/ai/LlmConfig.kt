package com.jarvis.ai

object LlmConfig {
    const val DEFAULT_PROVIDER = "groq"
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"
    const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

    const val STRONG_MODEL = "openai/gpt-oss-120b"

    // Gemini — update these when Google releases new stable versions.
    // gemini-2.0-flash was shut down; gemini-1.5-pro is EOL.
    // Use gemini-2.5-flash (current default) and gemini-2.5-pro (current strong).
    const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
    const val GEMINI_STRONG_MODEL = "gemini-2.5-pro"

    // OpenRouter — claude-3.5-sonnet remains valid; llama-3.3-70b-instruct is active via OpenRouter.
    const val OPENROUTER_DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct"
    const val OPENROUTER_STRONG_MODEL = "anthropic/claude-3.5-sonnet"

    // Ollama Local
    const val OLLAMA_DEFAULT_URL = "http://10.0.2.2:11434"
    const val OLLAMA_DEFAULT_MODEL = "llama3.2"

    val CANDIDATE_MODELS = listOf(
        "openai/gpt-oss-20b",
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "openai/gpt-oss-120b"
    )

    // Vision models — Groq vision: qwen/qwen3.6-27b (current multimodal offering as of 2026).
    // llama-3.2-11b-vision-preview is DEPRECATED on Groq.
    // gemini-2.0-flash is SHUT DOWN — use gemini-2.5-flash instead.
    const val DEFAULT_VISION_MODEL = "qwen/qwen3.6-27b"
    val VISION_CANDIDATE_MODELS = listOf(
        "qwen/qwen3.6-27b",           // Groq multimodal (current)
        "gemini-2.5-flash"             // Gemini multimodal (current)
    )
}
