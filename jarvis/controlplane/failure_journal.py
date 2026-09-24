"""
Failure Journal & Self-Healing Deferred Replay
Replicates Android FailureJournal.kt & SelfHealingSupervisor.kt for Linux Software.
"""

import time
import logging
from typing import List, Dict, Any

logger = logging.getLogger("FailureJournal")

class FailureJournal:
    shared = None

    def __init__(self):
        self.failures: List[Dict[str, Any]] = []
        self.deferred_tasks: List[Dict[str, Any]] = []

    def record_failure(self, goal: str, error: str, context: Dict[str, Any]):
        entry = {
            "timestamp": time.time(),
            "goal": goal,
            "error": error,
            "context": context
        }
        self.failures.append(entry)
        logger.warning(f"Failure recorded for goal '{goal}': {error}")

    def queue_deferred_task(self, goal: str, reason: str):
        self.deferred_tasks.append({
            "goal": goal,
            "reason": reason,
            "timestamp": time.time()
        })
        logger.info(f"Task deferred for later replay: '{goal}' ({reason})")

    def pop_deferred_tasks(self) -> List[Dict[str, Any]]:
        tasks = list(self.deferred_tasks)
        self.deferred_tasks.clear()
        return tasks

FailureJournal.shared = FailureJournal()
