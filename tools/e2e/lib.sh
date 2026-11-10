#!/usr/bin/env bash
# Shared helpers for on-device checks. Source this file; set DEV first.
E2E="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P=app.wisp.keyboard.debug
a() { adb -s "$DEV" shell "$@"; }
tap() { a input tap "$1" "$2"; sleep 0.2; }
hold() { a input swipe "$1" "$2" "$1" "$2" "$3"; sleep 0.3; }   # press and hold for $3 ms
drag() { a input swipe "$1" "$2" "$3" "$4" "${5:-400}"; sleep 0.3; }

# The one place key positions live: a 950x1158 Waydroid display (density 216), default layout, no number row.
# Letter keys come from swipe.py (K <letter> prints "x y"); the bottom row is y 1047 and the suggestion strip y 737.
read -r R1 R2 R3 <<< "$(python3 "$E2E/swipe.py" --rows)"; R4=1047; STRIP=737
K() { python3 "$E2E/swipe.py" --key "$1"; }
SHIFT="70 $R3"; BKSP="878 $R3"; SYM="63 $R4"; SWITCH="168 $R4"; COMMA="255 $R4"; LANG_KEY="340 $R4"; SPACE="560 $R4"; PERIOD="780 $R4"; ENTER="885 $R4"
TOOL_EMOJI="270 $STRIP"; NUMPAD_KEY="235 $R4"   # the emoji panel opens from the toolbar; the number pad from the symbols page (the "123" key beside the globe)
MIC="917 $STRIP"; SLOT_L="150 $STRIP"; SLOT_M="445 $STRIP"; SLOT_R="730 $STRIP"
PANEL_MIC="475 812"; PANEL_CANCEL="370 812"; PANEL_DONE="908 599"   # voice panel: pause/resume, cancel, the arrow that inserts and goes back
UNDO_CELL="$SLOT_L"                                                  # "Undo dictation" takes the first strip slot after an insert
# type <word>: taps each letter in turn, then gives the dictionary thread a moment to answer
type_word() { local c; for c in $(echo "$1" | sed 's/./& /g'); do tap $(K "$c"); done; sleep 0.9; }

# Launch the test field (extra args go to `am start`) and wait until the keyboard has been created and drawn.
launch_field() {
  # Each script starts with nothing learned, so what one script teaches the keyboard cannot change another's result. (learning.sh and training.sh manage state themselves.)
  [ -n "${KEEP_STATE:-}" ] || as_app "rm -rf files/personal files/wisp-settings.properties" >/dev/null 2>&1
  # Select another keyboard first: "ime set" is a no-op when Wisp is already current, and then no fresh start happens.
  a ime set com.android.inputmethod.latin/.LatinIME >/dev/null
  a am force-stop "$P"; a ime set "$P/app.wisp.keyboard.WispIme" >/dev/null; a logcat -c
  a am start -n "$P/app.wisp.keyboard.SpikeFieldActivity" "$@" >/dev/null
  local up=0
  for _ in $(seq 1 60); do a logcat -d -s WISP_PROBE | grep -q view_created && { up=1; break; }; sleep 0.25; done
  [ $up = 1 ] || echo "WARN: keyboard did not appear after launch" >&2
  sleep 2.8   # the keyboard window is still animating in for a moment after its view exists
  a input tap 475 300; sleep 0.5   # the first touch after launch is swallowed by window focus; spend it on the text area
}

start_field() {
  launch_field
}

field_text() {
  a uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  a cat /sdcard/u.xml | grep -o '<node[^>]*EditText[^>]*>' | head -1 | grep -o ' text="[^"]*"' | head -1 | sed 's/^ text="//; s/"$//'
}

expect() { # expect <label> <expected>
  local got; got="$(field_text)"
  if [ "$got" = "$2" ]; then echo "PASS $1: '$got'"; else echo "FAIL $1: expected '$2' got '$got'"; FAILED=1; fi
}

# Run a command inside the app's data directory. Phones: run-as. Waydroid refuses run-as, so use its root shell.
as_app() {
  if [ -z "${AS_MODE:-}" ]; then a run-as "$P" true >/dev/null 2>&1 && AS_MODE=runas || AS_MODE=waydroid; fi
  if [ "$AS_MODE" = runas ]; then a run-as "$P" sh -c "$*"; else sudo waydroid shell -- sh -c "cd /data/data/$P && $*"; fi
}

# Write the app's settings file: set_prefs voice_idle_minutes=0 debug_load_delay_ms=2500 incognito=false
set_prefs() {
  local lines="" kv
  for kv in "$@"; do lines+="$kv\n"; done
  as_app "printf '$lines' > files/wisp-settings.properties; chown \$(stat -c %u .) files/wisp-settings.properties" >/dev/null 2>&1
}
