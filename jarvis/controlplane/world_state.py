"""
Linux World State Store & System Telemetry Provider
"""

import subprocess
import time
import psutil
import logging
from typing import Dict, Any, Optional

logger = logging.getLogger("WorldStateStore")

class LinuxStateProvider:
    def get_cpu_usage(self) -> float:
        return psutil.cpu_percent()

    def get_memory_usage(self) -> Dict[str, float]:
        mem = psutil.virtual_memory()
        return {"total_mb": round(mem.total / 1024 / 1024, 1), "used_mb": round(mem.used / 1024 / 1024, 1), "percent": mem.percent}

    def get_battery_info(self) -> Dict[str, Any]:
        """Queries Linux battery info."""
        try:
            bat = psutil.sensors_battery()
            if bat:
                return {"percentage": int(bat.percent), "state": "charging" if bat.power_plugged else "discharging", "is_charging": bat.power_plugged}
        except Exception:
            pass
        return {"percentage": 100, "state": "unknown", "is_charging": True}

    def get_active_window(self) -> str:
        """Queries active Linux X11 window using xdotool."""
        try:
            res = subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True, text=True)
            if res.returncode == 0:
                return res.stdout.strip()
        except Exception:
            pass
        return "Desktop / Idle"

class WorldStateStore:
    shared = None

    def __init__(self):
        self.provider = LinuxStateProvider()
        self.last_update = time.time()
        self.cached_state: Dict[str, Any] = {}

    def get_current_state(self) -> Dict[str, Any]:
        self.cached_state = {
            "timestamp": time.time(),
            "cpu_percent": self.provider.get_cpu_usage(),
            "memory": self.provider.get_memory_usage(),
            "battery": self.provider.get_battery_info(),
            "active_window": self.provider.get_active_window()
        }
        return self.cached_state

WorldStateStore.shared = WorldStateStore()
