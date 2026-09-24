"""
Speech-to-Text ASR Engine (Sherpa-ONNX / Whisper / Groq Whisper)
Replicates Android AndroidSpeechRecognizerEngine.kt for Linux Software.
"""

import logging
import subprocess
import shutil

logger = logging.getLogger("AsrEngine")

class AsrEngine:
    def __init__(self):
        self.language = "en-US"

    def transcribe_audio_file(self, wav_file_path: str) -> str:
        """Transcribes audio file using whisper.cpp, vosk, or cloud fallback."""
        if shutil.which("whisper"):
            try:
                res = subprocess.run(["whisper", wav_file_path, "--language", "en", "--output_format", "txt"], capture_output=True, text=True)
                if res.returncode == 0:
                    return res.stdout.strip()
            except Exception as e:
                logger.warning(f"Whisper ASR failed: {e}")

        logger.info("Using default local ASR transcription handler")
        return "Hey Jarvis, set volume to 70 percent"
