#!/usr/bin/env bash
# Builds the release APKs and fails if the arm64 one is over the budget in docs/perf.md (45 MB).
set -euo pipefail
cd "$(dirname "$0")/../.."
BUDGET_MB="${BUDGET_MB:-45}"
./gradlew assembleRelease -q
apk="$(ls app/build/outputs/apk/release/*arm64-v8a*.apk | head -1)"
bytes=$(stat -c %s "$apk"); mb=$(( (bytes + 1048575) / 1048576 ))
echo "$(basename "$apk"): $((bytes/1024)) KB (${mb} MB, budget ${BUDGET_MB} MB)"
unzip -l "$apk" | awk '{print $1, $4}' | sort -rn | head -6
[ "$mb" -le "$BUDGET_MB" ] || { echo "OVER BUDGET"; exit 1; }
