#!/usr/bin/env bash
# On-device checks for suggestions and auto-correct (task 3.3). Usage: tools/e2e/suggest.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
# letter keys (QWERTY, 950px wide Waydroid display)
T="427 829"; E="239 829"; H="567 900"; W="145 829"; R="333 829"; O="803 829"; L="849 900"; D="285 900"
SP="610 1045"
SLOT_MID="475 752"
word() { for k in "$@"; do tap $k; done; sleep 0.9; }   # each argument is one "x y" pair   # type letters, give the dictionary thread time to answer
clear_field() { a input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 14); do a input keyevent KEYCODE_DEL; done; sleep 0.4; }

launch_field

word "$T" "$E" "$H"; tap $SP
expect "auto-correct: teh + space -> the" "the "

tap $BKSP
expect "one backspace takes the correction back" "teh"
tap $SP; sleep 0.4
expect "and it is not corrected again" "teh "

clear_field
word "$W" "$R" "$O" "$L" "$D"; adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/suggest_bar.png"
tap $SLOT_MID; sleep 0.4
expect "tapping the middle suggestion applies it" "world "

clear_field
word "$H" "$E" "$L" "$L" "$O"; tap $SP; sleep 0.4
expect "a correct word is left alone" "hello "
# editing tools in the strip (shown when no word is being typed): select all, cut, paste
clear_field
word "$H" "$E" "$L" "$L" "$O"; tap $SP; sleep 0.4
tap 88 752; tap 264 752; sleep 0.6                # Select all, Cut
expect "select all + cut empties the field" ""
tap 665 752; sleep 0.6                             # Paste (the strip has five tools: Select all, Cut, Copy, Paste, Private)
expect "paste brings the text back" "hello "
exit $FAILED
