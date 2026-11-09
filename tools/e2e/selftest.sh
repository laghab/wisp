#!/usr/bin/env bash
# Runs the device-side self tests (native code) and prints each result. Usage: selftest.sh <adb-serial> [name-prefix]
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; ONLY="${2:-}"
a logcat -c
if [ -n "$ONLY" ]; then a am start -n "$P/app.wisp.keyboard.SelfTestActivity" --es only "$ONLY" >/dev/null
else a am start -n "$P/app.wisp.keyboard.SelfTestActivity" >/dev/null; fi
for _ in $(seq 1 120); do a logcat -d -s SELFTEST | grep -q "DONE" && break; sleep 0.5; done
a logcat -d -s SELFTEST | grep SELFTEST | sed 's/.*SELFTEST *: //'
a logcat -d -s SELFTEST | grep -q "DONE failed=0" || exit 1
