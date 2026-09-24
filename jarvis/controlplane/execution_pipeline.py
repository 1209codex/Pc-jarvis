"""
Execution Pipeline Stage Tracker (PLANNING, EXECUTING, VERIFYING)
Replicates Android ExecutionPipeline.kt for Linux Software.
"""

import logging
from typing import Callable, List

logger = logging.getLogger("ExecutionPipeline")

class ExecutionPipeline:
    def __init__(self):
        self.current_stage = "IDLE"  # IDLE, PLANNING, EXECUTING, VERIFYING, COMPLETED, FAILED
        self.listeners: List[Callable[[str], None]] = []

    def add_listener(self, listener: Callable[[str], None]):
        self.listeners.append(listener)

    def set_stage(self, stage: str):
        self.current_stage = stage
        logger.info(f"Execution Pipeline Stage -> {stage}")
        for listener in self.listeners:
            try:
                listener(stage)
            except Exception as e:
                logger.error(f"Error notifying pipeline listener: {e}")
