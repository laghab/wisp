#!/usr/bin/env bash
# Cold-start benchmark for the keyboard: force-stops the app, focuses a text field so the IME starts,
# and reads the debug probe. Usage: tools/bench/startup.sh [device-serial] [runs]
set -euo pipefail
DEV="${1:-$(adb devices | awk 'NR==2{print $1}')}"
RUNS="${2:-10}"
P=app.wisp.keyboard.debug
a() { adb -s "$DEV" shell "$@"; }

a ime enable "$P/app.wisp.keyboard.WispIme" >/dev/null
a ime set "$P/app.wisp.keyboard.WispIme" >/dev/null

vals=()
for i in $(seq 1 "$RUNS"); do
  a ime set com.android.inputmethod.latin/.LatinIME >/dev/null
  a am force-stop "$P"; a ime set "$P/app.wisp.keyboard.WispIme" >/dev/null; a logcat -c
  a am start -n "$P/app.wisp.keyboard.SpikeFieldActivity" >/dev/null
  for _ in $(seq 1 100); do
    line=$(a logcat -d -s WISP_PROBE | grep first_draw || true)
    [ -n "$line" ] && break; sleep 0.1
  done
  us=$(echo "$line" | { grep -o '+[0-9]*us' || true; } | tr -d '+us')
  [ -n "$us" ] && vals+=("$us")
done
sorted=($(printf '%s\n' "${vals[@]}" | sort -n))
n=${#sorted[@]}
[ "$n" -gt 0 ] || { echo "no samples"; exit 1; }
echo "device=$DEV runs=$n"
echo "ime_create_to_first_draw_ms: median=$(( sorted[n/2] / 1000 )) p95=$(( sorted[(n*95)/100 >= n ? n-1 : (n*95)/100] / 1000 )) max=$(( sorted[n-1] / 1000 ))"
echo "pss_kb: $(a dumpsys meminfo "$P" | grep -E '^ *TOTAL ' | awk 'NR==1{print $2}')"
