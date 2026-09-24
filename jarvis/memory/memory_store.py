"""
Neural Memory Vault & SQLite Persistent Store
Replicates Android MemoryStore.kt for Linux Software.
"""

import sqlite3
import os
import time
import math
import logging
from typing import List, Dict, Any, Optional

logger = logging.getLogger("MemoryStore")

class MemoryItem:
    def __init__(self, key: str, value: str, category: str, confidence: float = 1.0, created_at: float = None):
        self.key = key
        self.value = value
        self.category = category  # USER_PREFERENCE, EPISODIC_EVENT, FACTUAL_KNOWLEDGE, HABIT_PATTERN
        self.confidence = confidence
        self.created_at = created_at or time.time()
        self.access_count = 1
        self.half_life_days = 30.0

    def compute_decayed_weight(self) -> float:
        age_days = (time.time() - self.created_at) / 86400.0
        decay = math.exp(-0.693 * age_days / self.half_life_days)
        return round(self.confidence * decay, 3)

class MemoryStore:
    def __init__(self, db_path: str = None):
        if not db_path:
            db_dir = os.path.expanduser("~/.config/jarvis")
            os.makedirs(db_dir, exist_ok=True)
            db_path = os.path.join(db_dir, "memory.db")
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    category TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    created_at REAL NOT NULL,
                    access_count INTEGER NOT NULL
                )
            """)
            conn.commit()

    def save_memory(self, key: str, value: str, category: str, confidence: float = 1.0):
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                INSERT INTO memories (key, value, category, confidence, created_at, access_count)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    category = excluded.category,
                    confidence = excluded.confidence,
                    access_count = access_count + 1
            """, (key, value, category, confidence, time.time()))
            conn.commit()

    def query_memories(self, category: Optional[str] = None, search: Optional[str] = None) -> List[MemoryItem]:
        query = "SELECT key, value, category, confidence, created_at, access_count FROM memories"
        params = []
        conditions = []

        if category:
            conditions.append("category = ?")
            params.append(category)
        if search:
            conditions.append("(key LIKE ? OR value LIKE ?)")
            params.append(f"%{search}%")
            params.append(f"%{search}%")

        if conditions:
            query += " WHERE " + " AND ".join(conditions)

        items = []
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(query, params)
            for row in cursor.fetchall():
                item = MemoryItem(row[0], row[1], row[2], row[3], row[4])
                item.access_count = row[5]
                items.append(item)
        return items

    def run_garbage_collection(self) -> int:
        """Evicts memories whose decayed weight falls below threshold (0.1)."""
        memories = self.query_memories()
        pruned_count = 0
        with sqlite3.connect(self.db_path) as conn:
            for m in memories:
                if m.compute_decayed_weight() < 0.1:
                    conn.execute("DELETE FROM memories WHERE key = ?", (m.key,))
                    pruned_count += 1
            conn.commit()
        logger.info(f"Garbage collection pruned {pruned_count} decayed memories")
        return pruned_count
