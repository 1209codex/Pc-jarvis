"""
Linux World State Store & System Telemetry Provider
Replicates Android WorldStateStore.kt & AndroidStateProvider.kt for Linux Software.
"""

import os
import shutil
import subprocess
import time
import logging
from typing import Dict, Any, Optional

logger = logging.getLogger("WorldStateStore")

class LinuxStateProvider:
    def get_cpu_usage(self) -> float:
        try:
            with open("/proc/stat", "r") as f:
                line = f.readline()
                fields = [float(x) for x in line.split()[1:]]
                idle = fields[3]
                total = sum(fields)
                return round(100.0 * (1.0 - idle / total), 1)
        except Exception:
            return 0.0

    def get_memory_usage(self) -> Dict[str, float]:
        try:
            mem_total, mem_available = 0.0, 0.0
            with open("/proc/meminfo", "r") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        mem_total = float(line.split()[1]) / 1024.0
                    elif line.startswith("MemAvailable:"):
                        mem_available = float(line.split()[1]) / 1024.0
            used = mem_total - mem_available
            pct = (used / mem_total * 100.0) if mem_total > 0 else 0.0
            return {"total_mb": round(mem_total, 1), "used_mb": round(used, 1), "percent": round(pct, 1)}
        except Exception:
            return {"total_mb": 0.0, "used_mb": 0.0, "percent": 0.0}

    def get_battery_info(self) -> Dict[str, Any]:
        """Queries Linux upower or sysfs battery info."""
        try:
            res = subprocess.run(["upower", "-i", "/org/freedesktop/UPower/devices/battery_BAT0"], capture_output=True, text=True)
            if res.returncode == 0:
                percentage = 100
                state = "charging"
                for line in res.stdout.splitlines():
                    if "percentage:" in line:
                        p_val = int(line.split(":")[1].strip().replace("%", ""))
                        if p_val > 0:
                            percentage = p_val
                    elif "state:" in line:
                        state = line.split(":")[1].strip()
                return {"percentage": percentage, "state": state, "is_charging": state in ["charging", "fully-charged"]}
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
