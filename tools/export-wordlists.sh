#!/usr/bin/env bash
# Exports every language's dictionary as word<TAB>probability into eval/ (used by the gesture evaluation and gate tests).
# Needs a connected device with the debug app installed. Usage: tools/export-wordlists.sh <adb-serial>
set -euo pipefail
cd "$(dirname "$0")/.."
DEV="${1:?adb serial}"
tools/e2e/selftest.sh "$DEV" export
mkdir -p eval
for f in $(adb -s "$DEV" shell ls /sdcard/Android/data/app.wisp.keyboard.debug/files/ | grep '^words_'); do
  adb -s "$DEV" pull "/sdcard/Android/data/app.wisp.keyboard.debug/files/$f" eval/ >/dev/null
  echo "eval/$f"
done
