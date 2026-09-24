"""
Smart Routine Engine & Network State Triggers
Replicates Android SmartRoutineEngine.kt & NetworkStateMonitor.kt for Linux Software.
"""

import logging
from typing import List, Dict, Any, Callable

logger = logging.getLogger("SmartRoutineEngine")

class SmartRoutine:
    def __init__(self, routine_id: str, trigger_event: str, actions: List[Dict[str, Any]]):
        self.routine_id = routine_id
        self.trigger_event = trigger_event  # WAKE, WIFI_CONNECTED, TIME_ALARM
        self.actions = actions

class SmartRoutineEngine:
    instance = None

    def __init__(self, tool_executor):
        self.tool_executor = tool_executor
        self.routines: List[SmartRoutine] = []

    def register_routine(self, routine: SmartRoutine):
        self.routines.append(routine)
        logger.info(f"Registered Routine: [{routine.routine_id}] on '{routine.trigger_event}'")

    async def trigger_event(self, event_name: str):
        for r in self.routines:
            if r.trigger_event == event_name:
                logger.info(f"Triggering Routine [{r.routine_id}]")
                for action in r.actions:
                    await self.tool_executor.execute_tool(action["tool"], action.get("args", {}))

SmartRoutineEngine.instance = SmartRoutineEngine
