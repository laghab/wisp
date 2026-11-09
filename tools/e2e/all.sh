#!/usr/bin/env bash
# Runs every on-device check. Usage: tools/e2e/all.sh <adb-serial>
DEV="${1:?adb serial}"; D="$(dirname "$0")"; rc=0
for t in typing fields switchkey panels suggest language gesture retype learning training nomodel voice style; do echo "== $t"; "$D/$t.sh" "$DEV" || rc=1; done
exit $rc
