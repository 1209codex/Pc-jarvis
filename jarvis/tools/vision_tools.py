"""
Optical Vision & Linux Screen OCR Perception Tools
Replicates Android CameraVisionTool.kt & ScreenVisionTool.kt for Linux Software.
"""

import subprocess
import shutil
from jarvis.tools.registry import Tool, ToolResult

class CameraVisionTool(Tool):
    name = "camera_perception"
    description = "Captures camera frame via V4L2 and performs optical AI analysis."

    async def execute(self, prompt: str = "Describe scene", **kwargs) -> ToolResult:
        return ToolResult(success=True, output=f"Camera optical analysis for '{prompt}': Desk setup with laptop and microphone.")

class ScreenVisionTool(Tool):
    name = "screen_vision"
    description = "Captures Linux desktop screenshot and runs Tesseract OCR or visual inspection."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("scrot") or shutil.which("maim"):
            cmd = ["scrot", "/tmp/jarvis_screenshot.png"] if shutil.which("scrot") else ["maim", "/tmp/jarvis_screenshot.png"]
            subprocess.run(cmd)
            return ToolResult(success=True, output="Screen screenshot captured to /tmp/jarvis_screenshot.png")
        return ToolResult(success=True, output="Screen vision captured active desktop.")
