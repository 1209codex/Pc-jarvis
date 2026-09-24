from jarvis.tools.registry import Tool, ToolResult
import subprocess
import os
import shutil

class ScreenCaptureTool(Tool):
    name = "take_screenshot"
    description = "Captures the user's Linux desktop screen and saves it as an image file."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("scrot"):
            filepath = os.path.expanduser("~/Desktop/jarvis_screenshot.png")
            res = subprocess.run(["scrot", filepath])
            if res.returncode == 0:
                return ToolResult(success=True, output=f"Screenshot saved to {filepath}")
        return ToolResult(success=False, error="scrot not installed on Linux system")

class WebcamCaptureTool(Tool):
    name = "take_webcam_picture"
    description = "Takes a picture using the Linux webcam via ffmpeg."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("ffmpeg"):
            filepath = os.path.expanduser("~/Desktop/jarvis_webcam.jpg")
            res = subprocess.run(["ffmpeg", "-f", "video4linux2", "-i", "/dev/video0", "-vframes", "1", filepath, "-y"], capture_output=True)
            if res.returncode == 0:
                return ToolResult(success=True, output=f"Webcam picture saved to {filepath}")
        return ToolResult(success=False, error="ffmpeg not installed or webcam unavailable")
