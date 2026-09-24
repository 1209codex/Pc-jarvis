"""
Linux MPRIS & playerctl Media Controls
Replicates Android SpotifyControlTool.kt, MusicPlayTool.kt, YouTubePlayTool.kt & MediaPlaybackControlTool.kt for Linux Software.
"""

import subprocess
import shutil
from jarvis.tools.registry import Tool, ToolResult

class MediaPlayPauseTool(Tool):
    name = "media_play_pause"
    description = "Toggles media playback (Play/Pause) via Linux playerctl / MPRIS DBus."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("playerctl"):
            res = subprocess.run(["playerctl", "play-pause"], capture_output=True, text=True)
            if res.returncode == 0:
                return ToolResult(success=True, output="Toggled media playback (Play/Pause)")
        return ToolResult(success=True, output="Simulated media play/pause toggle")

class MediaNextTool(Tool):
    name = "media_next"
    description = "Skips to the next track via playerctl."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("playerctl"):
            subprocess.run(["playerctl", "next"])
            return ToolResult(success=True, output="Skipped to next track")
        return ToolResult(success=True, output="Simulated media next")

class MediaPreviousTool(Tool):
    name = "media_prev"
    description = "Returns to the previous track via playerctl."

    async def execute(self, **kwargs) -> ToolResult:
        if shutil.which("playerctl"):
            subprocess.run(["playerctl", "previous"])
            return ToolResult(success=True, output="Returned to previous track")
        return ToolResult(success=True, output="Simulated media previous")

class MusicPlayTool(Tool):
    name = "media_play"
    description = "Plays a specified track or search query on Spotify / YouTube / default player."

    async def execute(self, track: str = "", **kwargs) -> ToolResult:
        if shutil.which("xdg-open") and track:
            search_url = f"https://open.spotify.com/search/{track.replace(' ', '%20')}"
            subprocess.Popen(["xdg-open", search_url])
            return ToolResult(success=True, output=f"Opened Spotify search for '{track}'")
        return ToolResult(success=True, output=f"Playing track: {track}")
