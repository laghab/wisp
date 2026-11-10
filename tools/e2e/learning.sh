#!/usr/bin/env bash
# On-device checks for on-device learning (tasks 5.1, 5.3). Usage: tools/e2e/learning.sh <adb-serial>
DEV="${1:?adb serial}"; KEEP_STATE=1; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
swipe() { python3 "$HERE/swipe.py" "$@" > /tmp/swipe.$$.sh; adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null; a sh /data/local/tmp/swipe.sh; sleep 0.9; }
clear_field() { a input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 30); do a input keyevent KEYCODE_DEL; done; sleep 0.4; }
personal() { as_app "cat files/personal/en_US.tsv" 2>/dev/null; }
hide() { a input keyevent KEYCODE_HOME; sleep 2.5; }    # the keyboard finishes its input: it saves what it learned and tells the swipe decoder

a am force-stop "$P"; as_app "rm -rf files/personal files/wisp-settings.properties" >/dev/null 2>&1   # start with nothing learned and incognito off

# 1. teach it a name: type it, then tap the "keep as typed" entry in the strip
launch_field
type_word raghav
tap $SLOT_L; sleep 0.5
expect "typing a new word and keeping it as typed" "raghav "
hide
n=$(personal | grep -c "raghav"); [ "$n" -ge 1 ] && echo "PASS the learned word was saved to disk" || { echo "FAIL not saved: $(personal)"; FAILED=1; }

# 2. a fresh keyboard process still knows it: it completes the prefix ...
launch_field
type_word ragh
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/learning_bar.png"
tap $SLOT_M; sleep 0.5
expect "after a restart the learned word completes the prefix" "raghav "

# 3. ... and can be swiped
clear_field
swipe raghav 3
expect "the learned name can be swiped" "raghav "

# 4. incognito: nothing typed is learned
set_prefs incognito=true; KEEP_STATE=1 launch_field           # Private mode on
type_word zraa                                                # letters of a made-up word
tap $SLOT_L; sleep 0.4
type_word zraa; tap $SLOT_L; sleep 0.4
hide
if personal | grep -q "zraa"; then echo "FAIL incognito still learned: $(personal | grep zraa)"; FAILED=1; else echo "PASS incognito: the word typed twice was not learned"; fi
n=$(personal | grep -c "raghav"); [ "$n" -ge 1 ] && echo "PASS earlier learning was kept" || { echo "FAIL raghav lost"; FAILED=1; }
exit $FAILED
