"""
Augmented Memory Pipeline & Context Builder
Replicates Android AugmentedMemoryPipeline.kt for Linux Software.
"""

from typing import Dict, List, Any
from jarvis.memory.memory_store import MemoryStore

class AugmentedMemoryPipeline:
    def __init__(self, memory_store: MemoryStore):
        self.memory_store = memory_store

    def build_memory_context(self, user_prompt: str) -> Dict[str, Any]:
        """Extracts relevant stored user facts and preferences to inject into LLM prompts."""
        all_prefs = self.memory_store.query_memories(category="USER_PREFERENCE")
        facts = self.memory_store.query_memories(category="FACTUAL_KNOWLEDGE")

        pref_map = {p.key: p.value for p in all_prefs if p.compute_decayed_weight() >= 0.2}
        fact_map = {f.key: f.value for f in facts if f.compute_decayed_weight() >= 0.2}

        return {
            "preferences": pref_map,
            "facts": fact_map
        }
