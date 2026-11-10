#!/usr/bin/env bash
# On-device checks for suggestions and auto-correct (task 3.3). Usage: tools/e2e/suggest.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
clear_field() { a input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 14); do a input keyevent KEYCODE_DEL; done; sleep 0.4; }

launch_field

type_word teh; tap $SPACE
expect "auto-correct: teh + space -> the" "the "

tap $BKSP
expect "one backspace takes the correction back" "teh"
tap $SPACE; sleep 0.4
expect "and it is not corrected again" "teh "

clear_field
type_word world; adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/suggest_bar.png"
tap $SLOT_M; sleep 0.4
expect "tapping the middle suggestion applies it" "world "

clear_field
type_word hello; tap $SPACE; sleep 0.4
expect "a correct word is left alone" "hello "
exit $FAILED
