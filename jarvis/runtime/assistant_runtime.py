"""
Central Assistant Runtime Orchestrator
"""

import asyncio
import logging
from typing import Dict, List, Any, Optional

from jarvis.runtime.subsystem_manager import SubsystemManager
from jarvis.tools.registry import ToolRegistry, ToolExecutor, ToolResult
from jarvis.tools.media_tools import MediaPlayPauseTool, MediaNextTool, MediaPreviousTool, MusicPlayTool
from jarvis.tools.system_tools import OpenApplicationTool, SetVolumeTool, BatteryStatusTool, NaturalLanguageCalculatorTool, SpeakTool, InstallSoftwareTool
from jarvis.tools.messaging_tools import ReadUnreadMessagesTool, SendMessageTool
from jarvis.tools.vision_tools import CameraVisionTool, ScreenVisionTool
from jarvis.tools.research_tools import WebSearchTool, DailyBriefingTool

logger = logging.getLogger("AssistantRuntime")

class AssistantRuntime:
    def __init__(self, config_dir: str = None):
        self.subsystems = SubsystemManager(config_dir=config_dir)
        self.tool_registry = ToolRegistry()
        self._register_default_tools()

        self.tool_executor = ToolExecutor(
            registry=self.tool_registry,
            policy_engine=self.subsystems.policy_engine,
            audit_sink=self._audit_callback
        )

    def _register_default_tools(self):
        tools = [
            MediaPlayPauseTool(), MediaNextTool(), MediaPreviousTool(), MusicPlayTool(),
            OpenApplicationTool(), SetVolumeTool(), BatteryStatusTool(), NaturalLanguageCalculatorTool(), SpeakTool(), InstallSoftwareTool(),
            ReadUnreadMessagesTool(), SendMessageTool(),
            CameraVisionTool(), ScreenVisionTool(),
            WebSearchTool(), DailyBriefingTool()
        ]
        for t in tools:
            self.tool_registry.register(t)

    def _audit_callback(self, tool_name: str, tier: str, reason: str, status: str, details: str):
        logger.info(f"Audit: {tool_name} [{tier}] {status} - {reason}")

    async def execute_command(self, user_prompt: str) -> Dict[str, Any]:
        """Runs complete autonomous loop: context enrichment -> LLM planning -> tool execution -> verification."""
        logger.info(f"Executing Assistant Command: '{user_prompt}'")

        # 1. RAG & Memory Context Building
        facts = self.subsystems.memory_store.query_memories(category="FACTUAL_KNOWLEDGE")
        prefs = self.subsystems.memory_store.query_memories(category="USER_PREFERENCE")
        mem_context = {
            "preferences": {p.key: p.value for p in prefs if p.compute_decayed_weight() >= 0.2},
            "facts": {f.key: f.value for f in facts if f.compute_decayed_weight() >= 0.2}
        }
        rag_context = self.subsystems.rag_store.retrieve_context(user_prompt)

        # 2. LLM Model Goal Planning
        available_tools = self.tool_registry.list_tools()
        goal_plan = await self.subsystems.model_router.plan_goal(user_prompt, mem_context, available_tools)

        # 3. Tool Step Execution
        step_results: List[ToolResult] = []

        for step in goal_plan.get("steps", []):
            tool_name = step.get("tool_name")
            arguments = step.get("arguments", {})
            result = await self.tool_executor.execute_tool(tool_name, arguments)
            step_results.append(result)

            if not result.success:
                logger.warning(f"Step {tool_name} failed: {result.error}")
                break

        # 4. Final Verification Stage
        all_success = all(r.success for r in step_results) if step_results else True
        spoken = goal_plan.get("final_response") or ("Task completed successfully sir." if all_success else "Task encountered an error during execution.")
        
        # Trigger Speech Synthesis
        self.subsystems.tts_engine.speak(spoken)

        return {
            "success": all_success,
            "spoken_response": spoken,
            "step_results": [r.to_dict() for r in step_results]
        }
