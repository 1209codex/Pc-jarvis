import asyncio
from jarvis.runtime.assistant_runtime import AssistantRuntime

def test_assistant_runtime_execution():
    async def _test():
        runtime = AssistantRuntime()
        result = await runtime.execute_command("set volume to 80")
        assert result["success"] is True
        assert "spoken_response" in result
        assert len(result["step_results"]) > 0
        assert result["step_results"][0]["success"] is True

    asyncio.run(_test())
