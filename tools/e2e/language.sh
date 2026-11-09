#!/usr/bin/env bash
# On-device checks for the language key and Hinglish (tasks 3.4, 5.6). Usage: tools/e2e/language.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
LANG_KEY="471 1045"; SP="610 1045"
word() { for k in "$@"; do tap $k; done; sleep 0.9; }       # each argument is one "x y" pair
swipe() { python3 "$HERE/swipe.py" "$@" > /tmp/swipe.$$.sh; adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null; a sh /data/local/tmp/swipe.sh; sleep 0.9; }
as_app "rm -f files/wisp-settings.properties" >/dev/null 2>&1         # start in English

launch_field
# English corrects the typo teh -> the
word "427 829" "239 829" "567 901"; tap $SP; sleep 0.4
expect "English: teh -> the" "the "

tap $LANG_KEY; sleep 0.6                                      # English -> Hinglish
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/lang_hinglish.png"
# Hinglish does not auto-correct: the same typo stays as typed
word "427 829" "239 829" "567 901"; tap $SP; sleep 0.4
expect "Hinglish: nothing is auto-corrected (teh stays)" "the teh "

# Hinglish words are known: "kya" typed stays, and swiping Hinglish and mixed English words works
word "755 901" "521 829" "97 901"; tap $SP; sleep 0.4        # k y a  (typed)
expect "Hinglish word typed" "the teh kya "
sleep 3                                                       # the swipe index for Hinglish is built on first use
swipe kaise 3
expect "swiping a Hinglish word" "the teh kya kaise "
swipe meeting 3
expect "swiping an English word while in Hinglish" "the teh kya kaise meeting "

tap $LANG_KEY; sleep 0.6                                      # Hinglish -> English
word "427 829" "239 829" "567 901"; tap $SP; sleep 0.4
expect "back in English the auto-correct is back" "the teh kya kaise meeting the "
exit $FAILED
