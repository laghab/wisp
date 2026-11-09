#!/usr/bin/env bash
# On-device checks for swipe typing (tasks 4.4 and 4.7). Usage: tools/e2e/gesture.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
swipe() {   # swipe <word> [wobble]
  python3 "$HERE/swipe.py" "$@" > /tmp/swipe.$$.sh
  adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null
  a sh /data/local/tmp/swipe.sh; sleep 0.9
}
clear_field() { a input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 30); do a input keyevent KEYCODE_DEL; done; sleep 0.4; }

launch_field
sleep 4      # the first swipe after install builds the word index (a second or two)

swipe hello 4
expect "swiping h-e-l-l-o types hello" "hello "
swipe world 4
expect "a second swipe follows with one space" "hello world "
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/gesture_bar.png"

tap $BKSP; sleep 0.4
expect "one backspace removes the whole swiped word" "hello "

clear_field
tap 427 829; tap 239 829; tap 567 900; sleep 0.9; tap 610 1045; sleep 0.5       # tapping still works: t e h space -> the
expect "tap typing still works next to swiping" "the "

clear_field
swipe keyboard 6
expect "a long word" "keyboard "
clear_field
swipe qxzv 4                                   # not a word: nothing should be inserted, the strip offers the letters
expect "an unsure swipe inserts nothing" ""
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/gesture_unsure.png"
exit $FAILED
