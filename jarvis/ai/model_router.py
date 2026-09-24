"""
Multi-Provider LLM Model Router & Planning Engine
Replicates Android ModelRouter.kt & MultiProviderLlmClient.kt for Linux Software.
"""

import json
import logging
import requests
from typing import Dict, List, Any, Optional
from jarvis.ai.llm_config import LlmConfig
from jarvis.controlplane.goal_manager import PlannedGoal, GoalStep

logger = logging.getLogger("ModelRouter")

class ModelRouter:
    def __init__(self, api_key: str = "", provider: str = "Groq", default_model: str = LlmConfig.DEFAULT_MODEL):
        self.api_key = api_key
        self.provider = provider
        self.default_model = default_model
        self.ollama_endpoint = "http://localhost:11434/api/generate"

    async def plan_goal(self, prompt: str, context: Dict[str, Any], available_tools: List[Dict[str, Any]] = None) -> PlannedGoal:
        """
        Queries selected LLM provider to construct structured goal steps.
        Falls back gracefully if network or credentials are unavailable.
        """
        tools_summary = ", ".join([t.get("name", "") for t in (available_tools or [])])
        sys_prompt = f"You are J.A.R.V.I.S., an autonomous Linux AI super-agent. Available tools: [{tools_summary}]."
        
        # 1. Attempt LLM API Call
        try:
            if self.provider == "Ollama":
                res = self._call_ollama(prompt, sys_prompt)
                if res:
                    return self._parse_llm_response(prompt, res)
            elif self.api_key:
                res = self._call_cloud_llm(prompt, sys_prompt)
                if res:
                    return self._parse_llm_response(prompt, res)
        except Exception as e:
            logger.warning(f"LLM API call failed: {e}. Falling back to Rule-Based Goal Decomposition.")

        # 2. Rule-Based / Local Heuristic Goal Decomposition Fallback
        return self._heuristic_goal_planner(prompt)

    def _call_ollama(self, prompt: str, system: str) -> Optional[str]:
        try:
            resp = requests.post(self.ollama_endpoint, json={
                "model": "llama3.2",
                "prompt": f"{system}\nUser: {prompt}\nJSON Goal Plan:",
                "stream": False
            }, timeout=5.0)
            if resp.status_code == 200:
                return resp.json().get("response", "")
        except Exception:
            pass
        return None

    def _call_cloud_llm(self, prompt: str, system: str) -> Optional[str]:
        url = "https://api.groq.com/openai/v1/chat/completions"
        headers = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
        payload = {
            "model": self.default_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.2
        }
        resp = requests.post(url, headers=headers, json=payload, timeout=8.0)
        if resp.status_code == 200:
            return resp.json()["choices"][0]["message"]["content"]
        return None

    def _parse_llm_response(self, prompt: str, llm_text: str) -> PlannedGoal:
        try:
            data = json.loads(llm_text)
            steps = [GoalStep(s["tool"], s.get("arguments", {}), s.get("description", "")) for s in data.get("steps", [])]
            return PlannedGoal(prompt, steps, data.get("final_response"))
        except Exception:
            return PlannedGoal(prompt, [], llm_text)

    def _heuristic_goal_planner(self, prompt: str) -> PlannedGoal:
        """Built-in Natural Language Classifier fallback for common Linux tasks."""
        lower = prompt.lower()
        steps = []
        response = ""

        if "volume" in lower:
            # Volume control intent
            import re
            numbers = re.findall(r'\d+', lower)
            vol = int(numbers[0]) if numbers else 50
            steps.append(GoalStep("set_volume", {"percent": vol}, f"Set system volume to {vol}%"))
            response = f"Setting system volume to {vol}%."

        elif "open" in lower or "launch" in lower:
            app_name = lower.replace("open", "").replace("launch", "").strip()
            steps.append(GoalStep("open_application", {"app_name": app_name}, f"Launch {app_name}"))
            response = f"Opening {app_name}."

        elif "battery" in lower or "status" in lower:
            steps.append(GoalStep("get_battery_status", {}, "Query battery status"))
            response = "Checking system battery status."

        elif "play" in lower:
            song = lower.replace("play", "").strip()
            steps.append(GoalStep("media_play", {"track": song}, f"Play media track '{song}'"))
            response = f"Playing {song}."

        else:
            steps.append(GoalStep("speak", {"text": f"I processed your request: '{prompt}'"}, "Acknowledge request"))
            response = f"Sir, I am processing your request: {prompt}"

        return PlannedGoal(prompt, steps, response)
