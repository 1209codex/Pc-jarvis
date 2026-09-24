"""
Text-to-Speech TTS Engine (Piper TTS / espeak-ng / Groq TTS)
Replicates Android AndroidTtsEngine.kt & GroqTtsEngine.kt for Linux Software.
"""

import subprocess
import shutil
import logging

logger = logging.getLogger("TtsEngine")

class TtsEngine:
    def __init__(self, pitch: float = 1.0, speech_rate: float = 1.0):
        self.pitch = pitch
        self.speech_rate = speech_rate

    def speak(self, text: str):
        """Synthesizes and speaks text out loud via Linux audio output."""
        logger.info(f"TTS Spoken Output: '{text}'")
        
        # 1. Try Piper TTS
        if shutil.which("piper"):
            try:
                subprocess.run(f"echo '{text}' | piper --model en_US-lessac-medium --output-raw | aplay -r 22050 -f S16_LE", shell=True)
                return
            except Exception:
                pass

        # 2. Try espeak-ng / espeak
        if shutil.which("espeak-ng") or shutil.which("espeak"):
            binary = "espeak-ng" if shutil.which("espeak-ng") else "espeak"
            try:
                subprocess.run([binary, text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except Exception:
                pass

        # 3. Try spd-say
        if shutil.which("spd-say"):
            try:
                subprocess.run(["spd-say", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return
            except Exception:
                pass
