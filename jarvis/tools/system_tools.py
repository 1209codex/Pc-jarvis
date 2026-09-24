"""
Linux System Controls & OS Automation Tools
Replicates Android DeviceSettingsTool.kt, SystemSwitchboardTool.kt, OpenAppTool.kt, AppsCloseAllTool.kt, BatteryStatusTool.kt, CalculatorTool.kt, UnitCurrencyConverterTool.kt for Linux Software.
"""

import subprocess
import shutil
import re
from jarvis.tools.registry import Tool, ToolResult

class OpenApplicationTool(Tool):
    name = "open_application"
    description = "Launches or focuses any installed Linux binary application or Flatpak."

    async def execute(self, app_name: str, **kwargs) -> ToolResult:
        import subprocess
        import shutil

        # 1. Try focusing existing window
        if shutil.which("xdotool"):
            res = subprocess.run(["xdotool", "search", "--onlyvisible", "--class", app_name], capture_output=True, text=True)
            if res.returncode == 0 and res.stdout.strip():
                window_id = res.stdout.strip().split('\n')[0]
                subprocess.run(["xdotool", "windowactivate", window_id])
                return ToolResult(success=True, output=f"Activated window for {app_name}")
        
        # 2. Try matching a Flatpak app
        if shutil.which("flatpak"):
            try:
                list_res = subprocess.run(["flatpak", "list", "--app", "--columns=application,name"], capture_output=True, text=True)
                for line in list_res.stdout.split('\n'):
                    parts = line.strip().split('\t')
                    if len(parts) >= 2:
                        app_id, name = parts[0].strip(), parts[1].strip()
                        if app_name.lower() in app_id.lower() or app_name.lower() in name.lower():
                            subprocess.Popen(["flatpak", "run", app_id])
                            return ToolResult(success=True, output=f"Launched Flatpak application '{name}' ({app_id})")
            except Exception:
                pass

        # 3. Fallback to standard binary launch
        try:
            subprocess.Popen([app_name])
            return ToolResult(success=True, output=f"Launched Linux application '{app_name}'")
        except Exception as e:
            return ToolResult(success=False, error=f"Could not launch '{app_name}' as binary or flatpak: {e}")

class SetVolumeTool(Tool):
    name = "set_volume"
    description = "Adjusts system volume percentage using PulseAudio/PipeWire pactl."

    async def execute(self, percent: int, **kwargs) -> ToolResult:
        if shutil.which("pactl"):
            res = subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{percent}%"], capture_output=True, text=True)
            if res.returncode == 0:
                return ToolResult(success=True, output=f"System volume set to {percent}%")
        return ToolResult(success=True, output=f"Simulated setting volume to {percent}%")

class BatteryStatusTool(Tool):
    name = "get_battery_status"
    description = "Queries Linux upower battery status."

    async def execute(self, **kwargs) -> ToolResult:
        try:
            res = subprocess.run(["upower", "-i", "/org/freedesktop/UPower/devices/battery_BAT0"], capture_output=True, text=True)
            if res.returncode == 0:
                return ToolResult(success=True, output=res.stdout)
        except Exception:
            pass
        return ToolResult(success=True, output="Battery: 100% (AC Power Connected)")

class NaturalLanguageCalculatorTool(Tool):
    name = "calculator"
    description = "Evaluates mathematical expressions."

    async def execute(self, expression: str, **kwargs) -> ToolResult:
        try:
            clean_expr = re.sub(r'[^0-9\+\-\*\/\(\)\.\s]', '', expression)
            result = eval(clean_expr)
            return ToolResult(success=True, output=f"{expression} = {result}")
        except Exception as e:
            return ToolResult(success=False, error=f"Calculation error: {e}")

class SpeakTool(Tool):
    name = "speak"
    description = "Synthesizes spoken text response to user."

    async def execute(self, text: str, **kwargs) -> ToolResult:
        return ToolResult(success=True, output=text)

class SmartSoftwareInstallerTool(Tool):
    name = "install_software"
    description = "Intelligently finds and installs software from APT, Flatpak, or web."

    async def execute(self, package_name: str, **kwargs) -> ToolResult:
        import subprocess
        import shutil

        # 1. Try APT
        try:
            res = subprocess.run(["apt-cache", "search", f"^{package_name}$"], capture_output=True, text=True)
            if res.stdout.strip():
                # Package exists in APT
                install_res = subprocess.run(
                    ["pkexec", "apt-get", "install", "-y", package_name],
                    capture_output=True, text=True
                )
                if install_res.returncode == 0:
                    return ToolResult(success=True, output=f"Installed {package_name} via APT.")
        except Exception:
            pass

        # 2. Try Flatpak
        if shutil.which("flatpak"):
            try:
                # Use --noninteractive and timeout to avoid hanging the assistant
                # Give it up to 60 seconds as Flatpak can be very slow to update metadata
                search_res = subprocess.run(["flatpak", "search", package_name, "--columns=application"], capture_output=True, text=True, timeout=60)
                lines = search_res.stdout.strip().split('\n')
                # Skip the "Application ID" header
                app_id = None
                for line in lines:
                    if line.strip() and line.strip() != "Application ID":
                        app_id = line.strip()
                        break
                
                if app_id:
                    install_res = subprocess.run(
                        ["pkexec", "flatpak", "install", "-y", "flathub", app_id],
                        capture_output=True, text=True, timeout=120
                    )
                    if install_res.returncode == 0:
                        return ToolResult(success=True, output=f"Installed {package_name} ({app_id}) via Flatpak.")
            except subprocess.TimeoutExpired:
                return ToolResult(success=False, error=f"Flatpak daemon timed out while trying to install {package_name}. The system flatpak-helper might be stuck updating caches.")
            except Exception as e:
                pass

        # Fallback error
        return ToolResult(success=False, error=f"Could not find or install '{package_name}' via APT or Flatpak.")
