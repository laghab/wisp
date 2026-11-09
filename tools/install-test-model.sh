#!/usr/bin/env bash
# Puts the Moonshine Tiny model (and a test sentence as the debug microphone) into the debug app on a device, for the voice tests.
# Downloads the model once into $MODEL_CACHE. Usage: tools/install-test-model.sh <adb-serial>
set -euo pipefail
DEV="${1:?adb serial}"; P=app.wisp.keyboard.debug
CACHE="${MODEL_CACHE:-$HOME/.cache/wisp-test-models}"; NAME=sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27
if [ ! -d "$CACHE/$NAME" ]; then
  mkdir -p "$CACHE"; curl -sL -o "$CACHE/m.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NAME.tar.bz2"; tar -xjf "$CACHE/m.tar.bz2" -C "$CACHE"; rm -f "$CACHE/m.tar.bz2"
fi
adb -s "$DEV" shell "rm -rf /data/local/tmp/wisp-model && mkdir -p /data/local/tmp/wisp-model"
for f in encoder_model.ort decoder_model_merged.ort tokens.txt; do adb -s "$DEV" push "$CACHE/$NAME/$f" /data/local/tmp/wisp-model/ >/dev/null; done
adb -s "$DEV" push "$CACHE/$NAME/test_wavs/0.wav" /data/local/tmp/wisp-model/debug_mic.wav >/dev/null
sudo waydroid shell -- sh -c "D=/data/data/$P; mkdir -p \$D/no_backup/models/moonshine-tiny-en \$D/files && cp /data/local/tmp/wisp-model/encoder_model.ort /data/local/tmp/wisp-model/decoder_model_merged.ort /data/local/tmp/wisp-model/tokens.txt \$D/no_backup/models/moonshine-tiny-en/ && cp /data/local/tmp/wisp-model/debug_mic.wav \$D/files/debug_mic.wav && chown -R \$(stat -c %u \$D) \$D/no_backup \$D/files"
echo "test model installed"
