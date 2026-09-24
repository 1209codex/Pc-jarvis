"""
Tool Registry & Execution Gateway Architecture
Replicates Android ToolRegistry.kt, Tool.kt, ToolExecutor.kt & LazyTool.kt for Linux Software.
"""

import asyncio
import logging
from typing import Dict, List, Any, Optional, Callable
from jarvis.foundation.policy_engine import PolicyEngine, AutonomyTier

logger = logging.getLogger("ToolRegistry")

class ToolResult:
    def __init__(self, success: bool, output: Any = "", error: Optional[str] = None):
        self.success = success
        self.output = output
        self.error = error

    def to_dict(self) -> Dict[str, Any]:
        return {"success": self.success, "output": str(self.output), "error": self.error}

class Tool:
    name: str = "base_tool"
    description: str = "Base execution tool"
    required_tier: AutonomyTier = AutonomyTier.TIER_1_LOW_RISK_LOCAL
    is_root_required: bool = False

    async def execute(self, **kwargs) -> ToolResult:
        raise NotImplementedError

class ToolRegistry:
    def __init__(self):
        self._tools: Dict[str, Tool] = {}

    def register(self, tool: Tool):
        self._tools[tool.name] = tool
        logger.info(f"Registered Tool: '{tool.name}' (Tier {tool.required_tier.name})")

    def get_tool(self, name: str) -> Optional[Tool]:
        return self._tools.get(name)

    def list_tools(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": t.name,
                "description": t.description,
                "tier": t.required_tier.name,
                "root": t.is_root_required
            } for t in self._tools.values()
        ]

class ToolExecutor:
    def __init__(self, registry: ToolRegistry, policy_engine: PolicyEngine, audit_sink: Optional[Callable] = None):
        self.registry = registry
        self.policy_engine = policy_engine
        self.audit_sink = audit_sink

    async def execute_tool(self, tool_name: str, arguments: Dict[str, Any]) -> ToolResult:
        tool = self.registry.get_tool(tool_name)
        if not tool:
            err = f"Tool '{tool_name}' not found in ToolRegistry"
            logger.error(err)
            return ToolResult(success=False, error=err)

        # 4D Policy Engine Security Validation
        allowed, reason = self.policy_engine.validate_action(tool_name, tool.required_tier, tool.is_root_required)
        if not allowed:
            logger.warning(f"Policy Engine BLOCKED tool '{tool_name}': {reason}")
            if self.audit_sink:
                self.audit_sink(tool_name, tool.required_tier.name, reason, "BLOCKED", str(arguments))
            return ToolResult(success=False, error=f"Security Policy Blocked Action: {reason}")

        try:
            logger.info(f"Executing tool '{tool_name}' with args {arguments}")
            if asyncio.iscoroutinefunction(tool.execute):
                result = await tool.execute(**arguments)
            else:
                result = tool.execute(**arguments)

            if self.audit_sink:
                self.audit_sink(tool_name, tool.required_tier.name, "Policy Allowed", "SUCCESS" if result.success else "FAILED", str(result.output))
            return result
        except Exception as e:
            logger.error(f"Execution exception in tool '{tool_name}': {e}")
            if self.audit_sink:
                self.audit_sink(tool_name, tool.required_tier.name, "Execution Exception", "EXCEPTED", str(e))
            return ToolResult(success=False, error=str(e))
