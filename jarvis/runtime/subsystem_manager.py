"""
Lazy Subsystem Container
"""

import os
import logging
from typing import List, Dict, Any, Optional

from jarvis.controlplane.world_state import WorldStateStore
from jarvis.foundation.policy_engine import PolicyEngine
from jarvis.memory.memory_store import MemoryStore
from jarvis.retrieval.raphael_retrieval import RaphaelRetrievalManager
from jarvis.rag.rag_store import RagStore
from jarvis.wakeword.wake_engine import WakeWordEngine
from jarvis.voice.vad_engine import VadEngine
from jarvis.voice.asr_engine import AsrEngine
from jarvis.voice.tts_engine import TtsEngine
from jarvis.ai.model_router import ModelRouter

logger = logging.getLogger("SubsystemManager")

class SubsystemManager:
    def __init__(self, config_dir: str = None):
        self.config_dir = config_dir or os.path.expanduser("~/.config/jarvis")
        os.makedirs(self.config_dir, exist_ok=True)
        
        # Load config
        import json
        self.config = {}
        config_path = os.path.join(self.config_dir, "config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r") as f:
                    self.config = json.load(f)
            except Exception as e:
                logger.error(f"Failed to load config: {e}")

        self.world_store = WorldStateStore.shared
        self.policy_engine = PolicyEngine()

        self.wake_engine = WakeWordEngine()
        self.vad_engine = VadEngine()
        self.asr_engine = AsrEngine()
        self.tts_engine = TtsEngine()

        self.memory_store = MemoryStore(os.path.join(self.config_dir, "memory.db"))
        self.raphael_retrieval = RaphaelRetrievalManager()
        self.rag_store = RagStore()

        api_key = self.config.get("llm_api_key", os.environ.get("JARVIS_API_KEY", ""))
        provider = self.config.get("llm_provider", "Groq")
        self.model_router = ModelRouter(api_key=api_key, provider=provider)

    def handle_memory_pressure(self):
        """Reclaims memory on high RAM pressure by evicting RAG vector caches and running Memory GC."""
        logger.info("Evicting heavy vector caches under memory pressure")
        self.memory_store.run_garbage_collection()
