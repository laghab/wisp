#!/usr/bin/env bash
# Typing latency benchmark: sends N real taps on letter keys and prints the keyboard's own latency
# statistics (touch-up to commit call, and draw time) plus janky-frame stats. Usage: typing.sh [serial] [taps]
set -uo pipefail
DEV="${1:-$(adb devices | awk 'NR==2{print $1}')}"; N="${2:-200}"
source "$(dirname "$0")/../e2e/lib.sh"
launch_field
a dumpsys gfxinfo "$P" reset >/dev/null; a logcat -c
# one adb shell, many taps: letters across the middle row
a "for i in \$(seq 1 $N); do input tap \$((60 + (i * 83) % 820)) 900; done"
sleep 1
echo "--- keyboard latency (debug probe)"
a logcat -d -s WISP_PROBE | grep -E "tap_up_to_commit|on_draw" | tail -2
echo "--- frames (gfxinfo)"
a dumpsys gfxinfo "$P" | grep -E "Total frames rendered|Janky frames|Number |50th percentile|90th percentile|95th percentile|99th percentile" | head -12
echo "--- text length: $(field_text | wc -c)"
