#!/usr/bin/env bash
# On-device check: swipe, delete it at once, type the word by hand -> the failed swipe teaches the swipe style (task 5.2 follow-up).
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
swipe() { python3 "$E2E/swipe.py" "$@" > /tmp/swipe.$$.sh; adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null; a sh /data/local/tmp/swipe.sh; sleep 0.9; }
style() { as_app "cat files/personal/gesture_style.txt" 2>/dev/null; }

launch_field; sleep 4
swipe hello 3
expect "the swipe inserted a word" "hello "
tap $BKSP; sleep 0.4      # (no text check here: reading the screen makes Android restart the input, which forgets the pending swipe)
# the user meant "help" (starts at h, ends at the key next to o): type it by hand
type_word help; tap $SPACE; sleep 0.5
expect "the swiped word was replaced by the word typed by hand (backspace removed all of it first)" "help "
a input keyevent KEYCODE_HOME; sleep 2.5            # the keyboard finishes its input and saves
st="$(style)"; echo "style file: $st"
read -r bx by ratio spread ev <<< "$st"
awk -v ev="${ev:-0}" 'BEGIN { exit !(ev >= 2.0) }' && echo "PASS the failed swipe was learned from (evidence $ev)" || { echo "FAIL no learning from the corrected swipe: '$st'"; FAILED=1; }
exit $FAILED
