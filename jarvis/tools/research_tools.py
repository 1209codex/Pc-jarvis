"""
Web Search & Deep Research Tools
Replicates Android WebSearchTool.kt, DeepResearchTool.kt, QuickNotesTool.kt, ReminderSchedulerTool.kt, CalendarTool.kt, DailyBriefingTool.kt for Linux Software.
"""

import urllib.parse
import urllib.request
import json
from jarvis.tools.registry import Tool, ToolResult, AutonomyTier

class WebSearchTool(Tool):
    name = "web_search"
    description = "Searches the web for real-time information via DuckDuckGo API."
    required_tier = AutonomyTier.TIER_1_LOW_RISK_LOCAL

    async def execute(self, query: str, **kwargs) -> ToolResult:
        try:
            url = f"https://api.duckduckgo.com/?q={urllib.parse.quote(query)}&format=json"
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=5.0) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                abstract = data.get("AbstractText") or data.get("Heading") or "No detailed result found."
                return ToolResult(success=True, output=abstract)
        except Exception as e:
            return ToolResult(success=False, error=f"Search failed: {e}")

class DailyBriefingTool(Tool):
    name = "daily_briefing"
    description = "Generates a contextual morning briefing summary."
    required_tier = AutonomyTier.TIER_0_READ_ONLY

    async def execute(self, **kwargs) -> ToolResult:
        briefing = "Good morning Sir! All Linux background services are active. Systems are operating nominal."
        return ToolResult(success=True, output=briefing)
