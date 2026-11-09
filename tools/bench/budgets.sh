#!/usr/bin/env bash
# Task 10.2: every budget from docs/perf.md that a script can check, in one run. Exits 1 if any is breached.
# Usage: tools/bench/budgets.sh <adb-serial> [--no-apk]
# Every budget can be overridden from the environment, so a breach can be provoked on purpose to check the gate, e.g.
#   BUDGET_TAP_P99_US=1 tools/bench/budgets.sh <serial> --no-apk     (must fail)
# Needs the debug build installed (the probes are compiled out of release builds) and, for the voice checks, the test model (tools/install-test-model.sh).
set -uo pipefail
DEV="${1:?adb serial}"; SKIP_APK="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUDGET_TAP_P99_US="${BUDGET_TAP_P99_US:-16000}"        # key press to commit, 99% within 16 ms
BUDGET_COLD_P95_MS="${BUDGET_COLD_P95_MS:-300}"        # first show after process start
BUDGET_PSS_KB="${BUDGET_PSS_KB:-81920}"                # typing only, under 80 MB
BUDGET_UNLOAD_KB="${BUDGET_UNLOAD_KB:-15360}"          # memory after voice unload within 15 MB
BUDGET_FINISH_MS="${BUDGET_FINISH_MS:-300}"            # final transcript after release
BUDGET_APK_MB="${BUDGET_APK_MB:-45}"
fail=0
check() { # name measured limit unit
  if [ -z "$2" ]; then echo "FAIL $1: no measurement"; fail=1; return; fi
  if awk -v a="$2" -v b="$3" 'BEGIN { exit !(a <= b) }'; then echo "PASS $1: $2 $4 (budget $3)"; else echo "FAIL $1: $2 $4 over budget $3"; fail=1; fi
}

# cold start and memory
out="$("$HERE/startup.sh" "$DEV" 10)"; echo "$out" | sed 's/^/    /'
cold="$(echo "$out" | sed -n 's/.*p95=\([0-9]*\).*/\1/p')"; pss="$(echo "$out" | sed -n 's/^pss_kb: \([0-9]*\).*/\1/p')"
check "cold start p95" "$cold" "$BUDGET_COLD_P95_MS" ms
check "typing-only memory (PSS)" "$pss" "$BUDGET_PSS_KB" KB

# key press to commit
out="$("$HERE/typing.sh" "$DEV" 200 2>&1)"
line="$(echo "$out" | grep tap_up_to_commit | tail -1)"; echo "    $line"
p99="$(echo "$line" | grep -o 'p99=[0-9.]*[a-z]*' | head -1)"
case "$p99" in *ms) v="$(echo "${p99#p99=}" | tr -d 'ms')"; p99_us="$(awk -v v="$v" 'BEGIN { printf "%d", v * 1000 }')";; *us) p99_us="$(echo "${p99#p99=}" | tr -d 'us' | cut -d. -f1)";; *) p99_us="";; esac
check "key press to commit p99" "$p99_us" "$BUDGET_TAP_P99_US" us

# the process and wake-lock rules
src="$HERE/../e2e/lib.sh"; DEV="$DEV"; source "$src"
launch_field >/dev/null 2>&1
wl="$(a dumpsys power | grep -c "$P" || true)"
check "wake locks held by Wisp" "$wl" 0 locks

# voice: memory after unload and finish time (needs the test model)
if "$HERE/../install-test-model.sh" "$DEV" >/dev/null 2>&1; then
  vout="$("$HERE/../e2e/voice.sh" "$DEV" 2>&1)"
  fin="$(echo "$vout" | sed -n 's/^finish after stop: \([0-9]*\) ms.*/\1/p' | tail -1)"
  check "final transcript after release" "$fin" "$BUDGET_FINISH_MS" ms
  echo "$vout" | grep -q "PASS memory returns to within 15 MB" && echo "PASS memory after voice unload within budget" || { echo "FAIL memory after voice unload"; fail=1; }
  echo "$vout" | grep -q "PASS typing alone starts no voice process" && echo "PASS typing alone loads no model" || { echo "FAIL typing alone loaded a model"; fail=1; }
else echo "SKIP voice budgets (no test model)"; fi

# gesture decode time (self-test on the device)
a am start -n "$P/app.wisp.keyboard.SelfTestActivity" >/dev/null 2>&1; sleep 25
g="$(a logcat -d -s SELFTEST | grep -i "decode" | tail -3)"; echo "$g" | sed 's/^/    /'

# apk size
if [ "$SKIP_APK" != "--no-apk" ]; then
  if BUDGET_MB="$BUDGET_APK_MB" "$HERE/apk-size.sh" >/tmp/apk-size.out 2>&1; then echo "PASS $(head -1 /tmp/apk-size.out)"; else echo "FAIL $(head -1 /tmp/apk-size.out)"; fail=1; fi
fi
exit $fail
