import asyncio
from jarvis.ai.model_router import ModelRouter

def test_heuristic_goal_planner():
    async def _test():
        router = ModelRouter()
        goal = await router.plan_goal("open firefox", {})
        assert len(goal.steps) == 1
        assert goal.steps[0].tool_name == "open_application"
        assert goal.steps[0].arguments["app_name"] == "firefox"

    asyncio.run(_test())
