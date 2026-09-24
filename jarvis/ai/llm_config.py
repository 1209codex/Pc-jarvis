"""
LLM Model Specifications & Multi-Provider Configs
Replicates Android LlmConfig.kt for Linux Software.
"""

class LlmModelSpec:
    def __init__(self, provider: str, model_id: str, context_window: int, supports_vision: bool = False):
        self.provider = provider
        self.model_id = model_id
        self.context_window = context_window
        self.supports_vision = supports_vision

class LlmConfig:
    DEFAULT_MODEL = "openai/gpt-oss-20b"
    DEEP_REASONING_MODEL = "openai/gpt-oss-120b"
    GEMINI_FLASH = "gemini-2.5-flash"
    GEMINI_PRO = "gemini-2.5-pro"
    QWEN_VISION = "qwen/qwen3.6-27b"
    OLLAMA_LOCAL = "llama3.2"

    AVAILABLE_MODELS = [
        LlmModelSpec("Groq", "openai/gpt-oss-20b", 128000),
        LlmModelSpec("Groq", "openai/gpt-oss-120b", 128000),
        LlmModelSpec("Gemini", "gemini-2.5-flash", 1000000, supports_vision=True),
        LlmModelSpec("Gemini", "gemini-2.5-pro", 2000000, supports_vision=True),
        LlmModelSpec("OpenRouter", "qwen/qwen3.6-27b", 64000, supports_vision=True),
        LlmModelSpec("Ollama", "llama3.2", 8192)
    ]
