#!/usr/bin/env bash
# Shared helpers for on-device checks. Source this file; set DEV first.
E2E="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P=app.wisp.keyboard.debug
a() { adb -s "$DEV" shell "$@"; }
tap() { a input tap "$1" "$2"; sleep 0.2; }
hold() { a input swipe "$1" "$2" "$1" "$2" "$3"; sleep 0.3; }   # press and hold for $3 ms
drag() { a input swipe "$1" "$2" "$3" "$4" "${5:-400}"; sleep 0.3; }

# Where things are comes from the keyboard itself: the debug build logs every key's rectangle and the strip's parts (tag WISP_GEOM), so
# the same scripts run on Waydroid and on a phone. launch_field calls read_geometry, which fills the names below.
KEYS="${TMPDIR:-/tmp}/wisp-keys-$DEV"; export WISP_KEYS="$KEYS"      # "code left top right bottom" per key; swipe.py reads it too
read -r W H <<< "$(adb -s "$DEV" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ')"
DPI="$(adb -s "$DEV" shell wm density | tail -1 | grep -o '[0-9]*$')"; px() { echo $(($1 * DPI / 216)); }      # px <n>: a finger distance measured on Waydroid (216 dpi), on this display
OTHER_IME="$(adb -s "$DEV" shell ime list -s | grep -v "^$P/" | head -1)"      # some other enabled keyboard, to switch away to
key() { awk -v c="$1" '$1 == c { printf "%d %d", ($2 + $4) / 2, ($3 + $5) / 2; exit }' "${2:-$KEYS}"; }      # key <code>: its centre
K() { key "$(printf '%d' "'$1")"; }                                                                          # K <letter>
# pop <code> <n>: item n (from 0) of the menu a held key opens
pop() { awk -v c="$1" -v n="$2" -v cell="$CELL" '$1 == c { printf "%d %d", $2 + cell * (n + 0.5), $3 - ($5 - $3) / 2; exit }' "$KEYS"; }
# keys_line [layout]: the newest report of the keys showing (or of one layout by name, e.g. emoji-bar)
keys_line() { a logcat -d -s WISP_GEOM | grep -o 'keys .*' | { if [ -n "${1:-}" ]; then grep "^keys $1 "; else grep -v '^keys emoji-bar '; fi; } | tail -1 | tr -d '\r'; }
# now <code> [layout]: a key on the page showing right now (symbols, number pad, the emoji panel's bar), which read_geometry has not stored
now() { keys_line "${2:-}" | tr ' ' '\n' | sed -n 's/^\(-\{0,1\}[0-9]*\):\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\)$/\1 \2 \3 \4 \5/p' > "$KEYS.now"; key "$1" "$KEYS.now"; }
read_geometry() {
  local line bar y
  line="$(keys_line)"; bar="$(a logcat -d -s WISP_GEOM | grep -o 'bar y=.*' | tail -1 | tr -d '\r')"
  [ -n "$line" ] && [ -n "$bar" ] || { echo "WARN: the keyboard reported no positions (is the debug build installed?)" >&2; return 1; }
  CELL="$(echo "$line" | sed -n 's/.* cell=\([0-9]*\) .*/\1/p')"
  echo "$line" | tr ' ' '\n' | sed -n 's/^\(-\{0,1\}[0-9]*\):\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\)$/\1 \2 \3 \4 \5/p' > "$KEYS"
  SHIFT="$(key -1)"; BKSP="$(key -2)"; ENTER="$(key -3)"; SYM="$(key -4)"; EMOJI="$(key -8)"; COMMA="$(key 44)"; SPACE="$(key 32)"; PERIOD="$(key 46)"
  R1="$(K q | cut -d' ' -f2)"; R2="$(K a | cut -d' ' -f2)"; R3="$(K z | cut -d' ' -f2)"; R4="${SPACE#* }"
  bar_part() { echo "$bar" | sed -n "s/.* $1=\([0-9]*\).*/\1/p"; }
  y="$(echo "$bar" | sed -n 's/^bar y=\([0-9]*\).*/\1/p')"; STRIP="$y"
  GRID="$(bar_part grid) $y"; MIC="$(bar_part mic) $y"; SLOT_L="$(bar_part s0) $y"; SLOT_M="$(bar_part s1) $y"; SLOT_R="$(bar_part s2) $y"
  UNDO_CELL="$SLOT_L"                                              # "Undo dictation" takes the first strip slot after an insert
  GLOBE_ITEM="$(pop 44 0)"; LANG_ITEM="$(pop 44 1)"                # the comma's menu: the globe first, the next language second
}
# Long-press menus: press a key, wait for its menu, slide to an item and let go.  menu_pick "<key x y>" "<item x y>"
menu_pick() { local kx ky ix iy; read -r kx ky <<< "$1"; read -r ix iy <<< "$2"
  a input motionevent DOWN "$kx" "$ky"; sleep 0.8; a input motionevent MOVE "$ix" "$iy"; sleep 0.2; a input motionevent UP "$ix" "$iy"; sleep 0.6; }
lang_next() { menu_pick "$COMMA" "$LANG_ITEM"; }
# type <word>: taps each letter in turn, then gives the dictionary thread a moment to answer
type_word() { local c; for c in $(echo "$1" | sed 's/./& /g'); do tap $(K "$c"); done; sleep 0.9; }

# Launch the test field (extra args go to `am start`) and wait until the keyboard has been created and drawn.
launch_field() {
  # Each script starts with nothing learned, so what one script teaches the keyboard cannot change another's result. (learning.sh and training.sh manage state themselves.)
  [ -n "${KEEP_STATE:-}" ] || as_app "rm -rf files/personal files/wisp-settings.properties" >/dev/null 2>&1
  # Select another keyboard first: "ime set" is a no-op when Wisp is already current, and then no fresh start happens.
  a ime set "$OTHER_IME" >/dev/null 2>&1
  a am force-stop "$P"; a ime enable "$P/app.wisp.keyboard.WispIme" >/dev/null; a ime set "$P/app.wisp.keyboard.WispIme" >/dev/null; a logcat -c
  a am start -n "$P/app.wisp.keyboard.SpikeFieldActivity" "$@" >/dev/null
  local up=0
  for _ in $(seq 1 60); do a logcat -d -s WISP_PROBE | grep -q view_created && { up=1; break; }; sleep 0.25; done
  [ $up = 1 ] || echo "WARN: keyboard did not appear after launch" >&2
  sleep 2.8   # the keyboard window is still animating in for a moment after its view exists
  a input tap $((W / 2)) $((H / 4)); sleep 0.5   # the first touch after launch is swallowed by window focus; spend it on the text area
  read_geometry
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
