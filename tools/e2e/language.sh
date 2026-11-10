#!/usr/bin/env bash
# On-device checks for the language key and Hinglish (tasks 3.4, 5.6). Usage: tools/e2e/language.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
swipe() { python3 "$E2E/swipe.py" "$@" > /tmp/swipe.$$.sh; adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null; a sh /data/local/tmp/swipe.sh; sleep 0.9; }
as_app "rm -f files/wisp-settings.properties" >/dev/null 2>&1         # start in English

launch_field
# English corrects the typo teh -> the
type_word teh; tap $SPACE; sleep 0.4
expect "English: teh -> the" "the "

tap $LANG_KEY; sleep 0.6                                      # English -> Hinglish
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/lang_hinglish.png"
# Hinglish does not auto-correct: the same typo stays as typed
type_word teh; tap $SPACE; sleep 0.4
expect "Hinglish: nothing is auto-corrected (teh stays)" "the teh "

# Hinglish words are known: "kya" typed stays, and swiping Hinglish and mixed English words works
type_word kya; tap $SPACE; sleep 0.4
expect "Hinglish word typed" "the teh kya "
sleep 3                                                       # the swipe index for Hinglish is built on first use
swipe kaise 3
expect "swiping a Hinglish word" "the teh kya kaise "
swipe meeting 3
expect "swiping an English word while in Hinglish" "the teh kya kaise meeting "

tap $LANG_KEY; sleep 0.6                                      # Hinglish -> English
type_word teh; tap $SPACE; sleep 0.4
expect "back in English the auto-correct is back" "the teh kya kaise meeting the "
exit $FAILED
