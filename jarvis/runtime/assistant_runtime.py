"""
Central Assistant Runtime Orchestrator
Replicates Android AssistantRuntime.kt for Linux Software.
"""

import asyncio
import logging
from typing import Dict, List, Any, Optional

from jarvis.runtime.subsystem_manager import SubsystemManager
from jarvis.tools.registry import ToolRegistry, ToolExecutor, ToolResult
from jarvis.tools.media_tools import MediaPlayPauseTool, MediaNextTool, MediaPreviousTool, MusicPlayTool
from jarvis.tools.system_tools import OpenApplicationTool, SetVolumeTool, BatteryStatusTool, NaturalLanguageCalculatorTool, SpeakTool
from jarvis.tools.messaging_tools import ReadUnreadMessagesTool, SendMessageTool
from jarvis.tools.vision_tools import CameraVisionTool, ScreenVisionTool
from jarvis.tools.research_tools import WebSearchTool, DailyBriefingTool
from jarvis.routine.smart_routine import SmartRoutineEngine
from jarvis.macro.macro_workflow import MacroWorkflowEngine
from jarvis.skilllearning.skill_evolution import AdaptiveSkillEvolution

from jarvis.tools.extended_tools import (
    CalendarTool, ClockTool, FlashlightTool, LocationTool, WeatherTool,
    FileManagerTool, TranslatorTool, UnitCurrencyConverterTool, DeepResearchTool,
    NotificationsTool, ReminderSchedulerTool, LogsTool
)

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

        self.smart_routine_engine = SmartRoutineEngine(self.tool_executor)
        self.macro_engine = MacroWorkflowEngine(self.tool_executor)
        self.skill_evolution = AdaptiveSkillEvolution()

        self.active_task = None
        self.tool_history: List[str] = []

    def _register_default_tools(self):
        tools = [
            MediaPlayPauseTool(), MediaNextTool(), MediaPreviousTool(), MusicPlayTool(),
            OpenApplicationTool(), SetVolumeTool(), BatteryStatusTool(), NaturalLanguageCalculatorTool(), SpeakTool(),
            ReadUnreadMessagesTool(), SendMessageTool(),
            CameraVisionTool(), ScreenVisionTool(),
            WebSearchTool(), DailyBriefingTool(),
            # Extended Tools
            CalendarTool(), ClockTool(), FlashlightTool(), LocationTool(), WeatherTool(),
            FileManagerTool(), TranslatorTool(), UnitCurrencyConverterTool(), DeepResearchTool(),
            NotificationsTool(), ReminderSchedulerTool(), LogsTool()
        ]
        for t in tools:
            self.tool_registry.register(t)

    def _audit_callback(self, tool_name: str, tier: str, reason: str, status: str, details: str):
        self.subsystems.task_state_manager.log_audit(
            task_id=self.active_task.task_id if self.active_task else "system",
            action=tool_name,
            risk=tier,
            reason=reason,
            status=status,
            details=details
        )

    async def execute_command(self, user_prompt: str) -> Dict[str, Any]:
        """Runs complete autonomous loop: context enrichment -> LLM planning -> tool execution -> verification."""
        logger.info(f"Executing Assistant Command: '{user_prompt}'")
        self.active_task = self.subsystems.task_state_manager.create_task(user_prompt)
        self.subsystems.execution_pipeline.set_stage("PLANNING")

        # 1. Check Safety Guardrails
        safe, guardrail_msg = self.subsystems.device_guardian.check_safety_guardrails()
        if not safe:
            logger.warning(f"Device Guardian Halted Execution: {guardrail_msg}")
            self.subsystems.execution_pipeline.set_stage("HALTED")
            return {"success": False, "spoken_response": f"Action halted: {guardrail_msg}", "step_results": []}

        # 2. RAG & Memory Context Building
        mem_context = self.subsystems.memory_pipeline.build_memory_context(user_prompt)
        rag_context = self.subsystems.rag_store.retrieve_context(user_prompt)

        # 3. LLM Model Goal Planning
        available_tools = self.tool_registry.list_tools()
        goal_plan = await self.subsystems.model_router.plan_goal(user_prompt, mem_context, available_tools)

        # 4. Tool Step Execution
        self.subsystems.execution_pipeline.set_stage("EXECUTING")
        step_results: List[ToolResult] = []

        for step in goal_plan.steps:
            result = await self.tool_executor.execute_tool(step.tool_name, step.arguments)
            step_results.append(result)
            self.tool_history.append(step.tool_name)
            self.subsystems.metrics.record_tool_call(step.tool_name)

            if not result.success:
                logger.warning(f"Step {step.tool_name} failed: {result.error}")
                self.subsystems.failure_journal.record_failure(user_prompt, str(result.error), step.arguments)
                break

        # 5. Mine frequent tool patterns for autonomous skill evolution
        self.skill_evolution.mine_frequent_workflow(self.tool_history)

        # 6. Final Verification Stage
        self.subsystems.execution_pipeline.set_stage("VERIFYING")
        all_success = all(r.success for r in step_results) if step_results else True
        self.subsystems.task_state_manager.complete_task(self.active_task.task_id, all_success)
        self.subsystems.execution_pipeline.set_stage("COMPLETED" if all_success else "FAILED")

        spoken = goal_plan.final_response or ("Task completed successfully sir." if all_success else "Task encountered an error during execution.")
        
        # Trigger Speech Synthesis
        self.subsystems.tts_engine.speak(spoken)

        return {
            "success": all_success,
            "spoken_response": spoken,
            "step_results": [r.to_dict() for r in step_results]
        }
