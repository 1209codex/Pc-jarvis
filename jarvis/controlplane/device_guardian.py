"""
Device Guardian & Safety Guardrails
Replicates Android DeviceGuardian.kt for Linux Software.
"""

import logging
from typing import Dict, Any, Tuple
from jarvis.controlplane.world_state import WorldStateStore

logger = logging.getLogger("DeviceGuardian")

class DeviceGuardian:
    def __init__(self, world_store: WorldStateStore):
        self.world_store = world_store
        self.min_battery_threshold = 10  # %
        self.max_cpu_threshold = 95.0   # %

    def check_safety_guardrails(self) -> Tuple[bool, str]:
        state = self.world_store.get_current_state()
        battery = state.get("battery", {})
        cpu = state.get("cpu_percent", 0.0)

        if battery.get("percentage", 100) < self.min_battery_threshold and not battery.get("is_charging", False):
            return False, f"Battery critically low ({battery.get('percentage')}%)"

        if cpu > self.max_cpu_threshold:
            return False, f"CPU usage critical ({cpu}%)"

        return True, "All safety guardrails clean"
