import os
import tempfile
from jarvis.memory.memory_store import MemoryStore

def test_memory_crud_and_gc():
    with tempfile.NamedTemporaryFile(suffix=".db") as tmp:
        store = MemoryStore(tmp.name)
        store.save_memory("user_name", "Rahul", "USER_PREFERENCE", confidence=1.0)
        
        memories = store.query_memories(category="USER_PREFERENCE")
        assert len(memories) == 1
        assert memories[0].value == "Rahul"

        # Decay check
        store.save_memory("old_fact", "outdated", "FACTUAL_KNOWLEDGE", confidence=0.01)
        pruned = store.run_garbage_collection()
        assert pruned == 1
