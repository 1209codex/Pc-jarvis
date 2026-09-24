import pytest
import asyncio
from jarvis.tools.extended_tools import (
    CalendarTool, ClockTool, FlashlightTool, LocationTool, WeatherTool,
    FileManagerTool, TranslatorTool, UnitCurrencyConverterTool, DeepResearchTool,
    NotificationsTool, ReminderSchedulerTool, LogsTool
)
from jarvis.autonomous.sensors_and_habits import PocketAndMotionManager, HabitManager
from jarvis.hardware.earbuds import EarbudHookInterceptor
import tempfile
import os

@pytest.mark.asyncio
async def test_extended_tools():
    # 1. Calendar
    cal = CalendarTool()
    res = await cal.execute()
    assert res.success
    assert "DeepMind Sync" in res.output

    # 2. Clock
    clk = ClockTool()
    res = await clk.execute()
    assert res.success
    assert "current time" in res.output

    # 3. Flashlight
    flash = FlashlightTool()
    res = await flash.execute(state=True)
    assert res.success

    # 4. Location
    loc = LocationTool()
    res = await loc.execute()
    assert res.success
    assert "San Francisco" in res.output

    # 5. Weather
    wea = WeatherTool()
    res = await wea.execute(location="Seattle")
    assert res.success
    assert "Seattle" in res.output

    # 6. FileManager
    fm = FileManagerTool()
    with tempfile.NamedTemporaryFile(mode='w', delete=False) as f:
        f.write("Hello J.A.R.V.I.S.")
        fname = f.name
    try:
        res = await fm.execute(path=fname)
        assert res.success
        assert "Hello J.A.R.V.I.S." in res.output
    finally:
        os.remove(fname)

    # 7. Translator
    tr = TranslatorTool()
    res = await tr.execute(text="Hello", target_lang="Spanish")
    assert res.success
    assert "Spanish" in res.output

@pytest.mark.asyncio
async def test_sensors_and_habits():
    manager = PocketAndMotionManager()
    # Assume default is True if xprintidle isn't installed or is acting up in headless tests
    is_present = manager.is_user_present()
    assert isinstance(is_present, bool)

    class MockMemoryStore:
        def __init__(self):
            self.saved = False
        def save_memory(self, **kwargs):
            self.saved = True

    ms = MockMemoryStore()
    habit = HabitManager(ms)
    habit.track_action("play_music")
    assert ms.saved
