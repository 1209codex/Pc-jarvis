#!/bin/bash
# Download Sherpa-ONNX ASR + TTS models for Android voice assistant.
set -euo pipefail

MODEL_DIR="$(cd "$(dirname "$0")" && pwd)/models"
mkdir -p "$MODEL_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-en-zh-2023-02-20.tar.bz2"
ASR_DIR="$TMP_DIR/sherpa-onnx-streaming-zipformer-bilingual-en-zh-2023-02-20"

echo "=== Downloading streaming Zipformer ASR model ==="
if [[ ! -f "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx" || ! -f "$MODEL_DIR/asr-tokens.txt" ]]; then
    curl -fL "$ASR_URL" -o "$TMP_DIR/asr-model.tar.bz2"
    tar xjf "$TMP_DIR/asr-model.tar.bz2" -C "$TMP_DIR"
    cp "$ASR_DIR/encoder-epoch-99-avg-1.int8.onnx" "$MODEL_DIR/"
    cp "$ASR_DIR/decoder-epoch-99-avg-1.int8.onnx" "$MODEL_DIR/"
    cp "$ASR_DIR/joiner-epoch-99-avg-1.int8.onnx" "$MODEL_DIR/"
    cp "$ASR_DIR/tokens.txt" "$MODEL_DIR/asr-tokens.txt"
    echo "ASR models downloaded"
else
    echo "ASR models already exist"
fi

echo
echo "=== Downloading VITS TTS model ==="
TTS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2"
TTS_DIR="$TMP_DIR/vits-piper-en_US-amy-medium"

if [[ ! -f "$MODEL_DIR/vits-model.onnx" || ! -f "$MODEL_DIR/tts-tokens.txt" ]]; then
    curl -fL "$TTS_URL" -o "$TMP_DIR/tts-model.tar.bz2"
    tar xjf "$TMP_DIR/tts-model.tar.bz2" -C "$TMP_DIR"
    cp "$TTS_DIR/model.onnx" "$MODEL_DIR/vits-model.onnx"
    cp "$TTS_DIR/tokens.txt" "$MODEL_DIR/tts-tokens.txt"
    rm -rf "$MODEL_DIR/vits-data"
    mkdir -p "$MODEL_DIR/vits-data"
    cp "$TTS_DIR"/espeak-ng-data/* "$MODEL_DIR/vits-data/" 2>/dev/null || true
    cp "$TTS_DIR"/lexicon.txt "$MODEL_DIR/vits-data/" 2>/dev/null || true
    echo "TTS model downloaded"
else
    echo "TTS models already exist"
fi

echo ""
echo "=== Validating Models ==="
test -f "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx" && echo "ASR Encoder: OK" || echo "ASR Encoder: MISSING"
test -f "$MODEL_DIR/decoder-epoch-99-avg-1.int8.onnx" && echo "ASR Decoder: OK" || echo "ASR Decoder: MISSING"
test -f "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx" && echo "ASR Joiner: OK" || echo "ASR Joiner: MISSING"
test -f "$MODEL_DIR/asr-tokens.txt" && echo "ASR Tokens: OK" || echo "ASR Tokens: MISSING"
test -f "$MODEL_DIR/vits-model.onnx" && echo "TTS Model: OK" || echo "TTS Model: MISSING"
test -f "$MODEL_DIR/tts-tokens.txt" && echo "TTS Tokens: OK" || echo "TTS Tokens: MISSING"

echo ""
echo "Next: ./deploy.sh"
