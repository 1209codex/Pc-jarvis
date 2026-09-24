#!/usr/bin/env python3
"""
Jarvis Live Telemetry & Command Monitor
Streams and parses adb logcat in real-time to monitor:
- Input / Utterance / Goal
- Function / Skill / Tool working at each step
- Output / LLM Reasoning / TTS Response
- Final Result & Status
- Writes all telemetry and command execution results directly to logs_result.md & logs_result.log
"""

import sys
import os
import re
import subprocess
import time
from datetime import datetime

WORKSPACE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD_LOG_FILE = os.path.join(WORKSPACE_DIR, "logs_result.md")
RAW_LOG_FILE = os.path.join(WORKSPACE_DIR, "logs_result.log")

# ANSI Color Codes
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
RED = "\033[31m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
BLUE = "\033[34m"
MAGENTA = "\033[35m"
CYAN = "\033[36m"
WHITE = "\033[37m"
BG_BLUE = "\033[44m"
BG_MAGENTA = "\033[45m"

BANNER = f"""
{CYAN}{BOLD}======================================================================
  🤖 JARVIS TELEMETRY MONITOR (Live Stream & Logger)
  Logging to:
    - {MD_LOG_FILE}
    - {RAW_LOG_FILE}
  Monitoring: [INPUT] -> [FUNCTION/TOOL] -> [OUTPUT] -> [RESULT]
======================================================================{RESET}
"""

TAG_FILTERS = [
    "VoicePipeline",
    "VoiceEngine",
    "AndroidSpeechEngine",
    "AssistantRuntime",
    "AgentKernel",
    "ToolExecutor",
    "AndroidTtsEngine",
    "GroqTtsEngine",
    "AppAutopilotTool",
    "ScreenVisionTool",
    "WhatsAppTool",
    "ClockTool",
    "WeatherTool",
    "TranslatorTool",
    "CameraVisionTool",
    "EarbudsAssistantEngine",
    "AutonomousDaemon",
    "AndroidRuntime"
]

def append_to_file(filepath, content):
    try:
        with open(filepath, "a", encoding="utf-8") as f:
            f.write(content + "\n")
    except Exception as e:
        print(f"{RED}Failed to write to {filepath}: {e}{RESET}")

def format_event(tag, msg, raw_line):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    time_short = datetime.now().strftime("%H:%M:%S.%f")[:-3]

    # Write raw entry to raw log file
    append_to_file(RAW_LOG_FILE, f"[{now}] [{tag}] {msg}")

    # 1. INPUT / VOICE / UTTERANCE
    if "Runtime executing command:" in msg or "Final Speech Result:" in msg or "starting closed-loop execution" in msg:
        clean_input = msg
        for prefix in ["Runtime executing command:", "Final Speech Result:"]:
            if prefix in clean_input:
                clean_input = clean_input.split(prefix, 1)[1].strip()
        print(f"\n{BG_BLUE}{WHITE}{BOLD} 🎤 [COMMAND INPUT] {RESET} {CYAN}{time_short}{RESET}")
        print(f"  {BOLD}Utterance/Goal:{RESET} {GREEN}{clean_input}{RESET}")
        
        md_entry = (
            f"\n### 🎤 Command: `{clean_input}`\n"
            f"- **Timestamp**: `{now}`\n"
            f"- **Event**: `COMMAND_INPUT`\n"
        )
        append_to_file(MD_LOG_FILE, md_entry)
        return

    # 2. FUNCTION / TOOL / PLANNER DISPATCH
    if "Executing tool" in msg:
        match = re.search(r"Executing tool '([^']+)' with params (.*)", msg)
        if match:
            tool_name = match.group(1)
            params = match.group(2)
            print(f"  {YELLOW}{BOLD}⚡ [FUNCTION RUNNING]{RESET} {BOLD}{tool_name}{RESET}")
            print(f"     {DIM}Parameters:{RESET} {CYAN}{params}{RESET}")
            md_entry = f"- **Function/Tool**: `{tool_name}` (Params: `{params}`)"
        else:
            print(f"  {YELLOW}{BOLD}⚡ [FUNCTION RUNNING]{RESET} {msg}")
            md_entry = f"- **Function/Tool**: `{msg}`"
        append_to_file(MD_LOG_FILE, md_entry)
        return

    if "Matched skill" in msg:
        print(f"  {MAGENTA}{BOLD}🎯 [SKILL MATCHED]{RESET} {msg}")
        append_to_file(MD_LOG_FILE, f"- **Matched Skill**: `{msg}`")
        return

    if "Executing fast-path plan" in msg or "Resolved intent:" in msg:
        print(f"  {BLUE}{BOLD}🧭 [INTENT/PLAN]{RESET} {msg}")
        append_to_file(MD_LOG_FILE, f"- **Intent/Plan**: `{msg}`")
        return

    if "Auto-granting approval" in msg:
        print(f"  {GREEN}{BOLD}🔓 [AUTONOMOUS ACTION]{RESET} {msg}")
        append_to_file(MD_LOG_FILE, f"- **Autonomous Approval**: `{msg}`")
        return

    # 3. OUTPUT / TTS / LLM
    if "TTS speaking" in msg or "AndroidTtsEngine" in tag or "GroqTtsEngine" in tag:
        if "speaking" in msg.lower() or "speak" in msg.lower() or "synthesiz" in msg.lower():
            print(f"  {MAGENTA}{BOLD}🔊 [VOICE OUTPUT]{RESET} {WHITE}{msg}{RESET}")
            append_to_file(MD_LOG_FILE, f"- **Voice Output**: `{msg}`")
            return

    if "[EXPERIENCE]" in msg or "[RESEARCH]" in msg or "[MULTI-TASK]" in msg:
        print(f"  {CYAN}{BOLD}🧠 [REASONING ENGINE]{RESET} {msg}")
        append_to_file(MD_LOG_FILE, f"- **Reasoning**: `{msg}`")
        return

    # 4. RESULT / COMPLETION
    if "Execution result:" in msg or "Tool execution completed" in msg or "Agent execution completed" in msg:
        print(f"  {GREEN}{BOLD}✅ [RESULT]{RESET} {GREEN}{msg}{RESET}")
        append_to_file(MD_LOG_FILE, f"- **Result**: `{msg}`\n- **Status**: ✅ `SUCCESS`\n")
        return

    if "Verification notice" in msg:
        print(f"  {YELLOW}{BOLD}🔍 [VERIFICATION]{RESET} {msg}")
        append_to_file(MD_LOG_FILE, f"- **Verification**: `{msg}`")
        return

    # 5. ERRORS & WARNINGS
    if " E " in raw_line or "AndroidRuntime" in tag or "failed" in msg.lower() or "exception" in msg.lower() or "crash" in msg.lower():
        print(f"  {RED}{BOLD}❌ [ERROR/FAILURE]{RESET} {RED}{tag}: {msg}{RESET}")
        append_to_file(MD_LOG_FILE, f"- **Error**: ❌ `{tag}: {msg}`")
        return

    if " W " in raw_line or "[GUARD]" in msg or "[RETRY]" in msg or "[RECOVERY]" in msg:
        print(f"  {YELLOW}{BOLD}⚠️  [WARNING/GUARD]{RESET} {YELLOW}{tag}: {msg}{RESET}")
        append_to_file(MD_LOG_FILE, f"- **Warning**: ⚠️ `{tag}: {msg}`")
        return

    # General Informational Telemetry
    print(f"  {DIM}[{time_short}] {tag}: {msg}{RESET}")


def run_monitor():
    print(BANNER)
    print(f"{DIM}Connecting to adb logcat...{RESET}")

    # Build adb logcat command
    adb_cmd = ["adb", "logcat", "-v", "time"]
    for tag in TAG_FILTERS:
        adb_cmd.extend(["-s", f"{tag}:V"])

    while True:
        try:
            # Check device availability
            devices_out = subprocess.check_output(["adb", "devices"], text=True)
            devices = [line.split()[0] for line in devices_out.strip().splitlines()[1:] if line.strip() and not line.startswith("*")]

            if not devices:
                print(f"{YELLOW}Waiting for Android device / emulator to connect via adb...{RESET}", end="\r", flush=True)
                time.sleep(2)
                continue

            print(f"{GREEN}✓ Connected to device(s): {', '.join(devices)}. Streaming logs...{RESET}\n")

            proc = subprocess.Popen(
                adb_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1
            )

            for line in iter(proc.stdout.readline, ''):
                if not line:
                    break
                line_str = line.strip()
                if not line_str:
                    continue

                # Parse logcat time format: "09-23 00:58:10.123 I/Tag(pid): message"
                match = re.search(r"([VDIWEF])\/([A-Za-z0-9_\-]+)\s*\(\s*\d+\s*\):\s*(.*)", line_str)
                if match:
                    level, tag, message = match.groups()
                    format_event(tag, message, line_str)
                else:
                    print(f"{DIM}{line_str}{RESET}")

        except KeyboardInterrupt:
            print(f"\n{YELLOW}Monitor stopped by user.{RESET}")
            break
        except Exception as e:
            print(f"{RED}Error in monitor stream: {e}. Retrying in 3s...{RESET}")
            time.sleep(3)


if __name__ == "__main__":
    run_monitor()
