"""
Linux Desktop Sensors, Presence & Habits
Replicates Android PocketAndMotionManager, HabitManager, & Clipboard hooks.
"""

import subprocess
import shutil
import logging
import time

logger = logging.getLogger("SensorsAndHabits")

class PocketAndMotionManager:
    """Uses X11 xprintidle to replicate pocket/proximity presence detection on Linux."""
    def __init__(self):
        self.idle_timeout_ms = 120000 # 2 minutes idle = User away

    def is_user_present(self) -> bool:
        if shutil.which("xprintidle"):
            try:
                res = subprocess.run(["xprintidle"], capture_output=True, text=True)
                if res.returncode == 0:
                    idle_ms = int(res.stdout.strip())
                    return idle_ms < self.idle_timeout_ms
            except Exception:
                pass
        return True # Default assume present

class HabitManager:
    """Tracks frequency of actions to recommend smart routines."""
    def __init__(self, memory_store):
        self.memory_store = memory_store

    def track_action(self, action_name: str):
        logger.info(f"Tracking habit for action: {action_name}")
        self.memory_store.save_memory(
            key=f"habit_{action_name}",
            value=str(time.time()),
            category="HABIT_PATTERN",
            confidence=1.0
        )

class ClipboardManager:
    """Linux Clipboard integration via wl-clipboard or xclip."""
    @staticmethod
    def get_clipboard() -> str:
        if shutil.which("wl-paste"):
            return subprocess.run(["wl-paste"], capture_output=True, text=True).stdout.strip()
        elif shutil.which("xclip"):
            return subprocess.run(["xclip", "-o", "-selection", "clipboard"], capture_output=True, text=True).stdout.strip()
        return ""
