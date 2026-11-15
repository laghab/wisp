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
COPY='mkdir -p no_backup/models/moonshine-tiny-en files && cp /data/local/tmp/wisp-model/encoder_model.ort /data/local/tmp/wisp-model/decoder_model_merged.ort /data/local/tmp/wisp-model/tokens.txt no_backup/models/moonshine-tiny-en/ && cp /data/local/tmp/wisp-model/debug_mic.wav files/debug_mic.wav'
# A phone lets the debug app's own user do the copy (run-as); Waydroid refuses run-as, so its root shell does it and hands the files over.
if adb -s "$DEV" shell run-as "$P" true >/dev/null 2>&1; then adb -s "$DEV" shell "run-as $P sh" <<< "$COPY"
else sudo waydroid shell -- sh -c "cd /data/data/$P && $COPY && chown -R \$(stat -c %u .) no_backup files"; fi
adb -s "$DEV" shell "run-as $P ls no_backup/models/moonshine-tiny-en/tokens.txt" >/dev/null 2>&1 || sudo waydroid shell -- ls "/data/data/$P/no_backup/models/moonshine-tiny-en/tokens.txt" >/dev/null 2>&1 || { echo "the model did not reach the app's folder" >&2; exit 1; }
echo "test model installed"
