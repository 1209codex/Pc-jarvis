"""
Goal Manager & Multi-Step Autonomous Planner
Replicates Android GoalManager.kt for Linux Software.
"""

import uuid
from typing import List, Dict, Any, Optional

class GoalStep:
    def __init__(self, tool_name: str, arguments: Dict[str, Any], description: str):
        self.step_id = uuid.uuid4().hex[:6]
        self.tool_name = tool_name
        self.arguments = arguments
        self.description = description
        self.completed = False

class PlannedGoal:
    def __init__(self, goal_text: str, steps: List[GoalStep], final_response: Optional[str] = None):
        self.goal_id = uuid.uuid4().hex[:8]
        self.goal_text = goal_text
        self.steps = steps
        self.final_response = final_response

class GoalManager:
    shared = None

    def __init__(self):
        self.active_goals: List[PlannedGoal] = []

    def register_goal(self, goal: PlannedGoal):
        self.active_goals.append(goal)

    def mark_step_complete(self, goal_id: str, step_id: str):
        for g in self.active_goals:
            if g.goal_id == goal_id:
                for step in g.steps:
                    if step.step_id == step_id:
                        step.completed = True

GoalManager.shared = GoalManager()
