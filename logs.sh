#!/bin/bash
# View live logs from Jarvis voice pipeline
adb logcat -s \
    VoicePipeline:V \
    SherpaAsr:V \
    OllamaLlm:V \
    SherpaTts:V \
    AudioCapture:V \
    AndroidRuntime:E
