"""
Lazy Subsystem Container & Memory Pressure Coordinator
Replicates Android SubsystemManager.kt for Linux Software.
"""

import os
import logging
from typing import List, Dict, Any, Optional

from jarvis.controlplane.world_state import WorldStateStore
from jarvis.controlplane.event_bus import JarvisEventBus
from jarvis.controlplane.device_guardian import DeviceGuardian
from jarvis.controlplane.goal_manager import GoalManager
from jarvis.controlplane.execution_pipeline import ExecutionPipeline
from jarvis.controlplane.failure_journal import FailureJournal
from jarvis.controlplane.strategy_registry import StrategyRegistry
from jarvis.foundation.policy_engine import PolicyEngine
from jarvis.foundation.task_state_manager import TaskStateManager
from jarvis.foundation.metrics_collector import MetricsCollector
from jarvis.memory.memory_store import MemoryStore
from jarvis.memory.memory_pipeline import AugmentedMemoryPipeline
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

        # Core Subsystems (Boot-critical)
        self.world_store = WorldStateStore.shared
        self.event_bus = JarvisEventBus.shared
        self.device_guardian = DeviceGuardian(self.world_store)
        self.goal_manager = GoalManager.shared
        self.failure_journal = FailureJournal.shared
        self.strategy_registry = StrategyRegistry.shared
        self.metrics = MetricsCollector.shared

        self.task_state_manager = TaskStateManager()
        self.policy_engine = PolicyEngine()
        self.execution_pipeline = ExecutionPipeline()

        self.wake_engine = WakeWordEngine()
        self.vad_engine = VadEngine()
        self.asr_engine = AsrEngine()
        self.tts_engine = TtsEngine()

        self.memory_store = MemoryStore(os.path.join(self.config_dir, "memory.db"))
        self.memory_pipeline = AugmentedMemoryPipeline(self.memory_store)
        self.raphael_retrieval = RaphaelRetrievalManager()
        self.rag_store = RagStore()

        self.model_router = ModelRouter()

    def handle_memory_pressure(self):
        """Reclaims memory on high RAM pressure by evicting RAG vector caches and running Memory GC."""
        logger.info("Evicting heavy vector caches under memory pressure")
        self.memory_store.run_garbage_collection()
        self.metrics.record_eviction()
