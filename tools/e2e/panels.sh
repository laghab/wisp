#!/usr/bin/env bash
# On-device checks for the symbols and emoji panels (task 2.5). Usage: tools/e2e/panels.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
launch_field

# symbols page 1: ?123, then digits and an @ symbol, then ABC back to letters
tap $SYM; tap $(K q); tap $(K w); tap $(K a)               # 1 2 @
tap $SYM; tap $(K q)                                                     # ABC, then q
expect "symbols page types digits and symbols, ABC returns to letters" "12@q"

# symbols page 2 via the =< key, then back
tap $SYM; tap $SHIFT; tap $(K q)                                   # ?123, =<, ~
tap $SHIFT; tap $(K q)                                             # ?123 (back to page 1), 1
expect "second symbols page and back" "12@q~1"
tap $SYM
a input keyevent KEYCODE_MOVE_END >/dev/null

# number pad: the "123" key on the symbols page opens a pad with operators on the left and symbols on the right of the digits; ABC returns
launch_field
tap $SYM; tap $NUMPAD_KEY; sleep 0.4; adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/panel_numpad.png"
tap 475 813; tap 665 891; tap 475 969; tap 96 735; tap 96 1047; tap $(K q)    # 5 9 0 +  then ABC, q
expect "number pad types digits and operators and ABC returns to letters" "590+q"

# emoji panel: open from letters, pick the first emoji, close via ABC
launch_field
tap $TOOL_EMOJI; adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/panel_emoji.png"
tap 60 890                                                               # top-left emoji
tap 90 1045                                                              # bottom bar ABC (1.5 weight, left)
tap $(K q)                                                              # q
got="$(field_text)"
case "$got" in "&#128512;q") echo "PASS emoji panel inserts an emoji and ABC returns: '$got'";; *) echo "FAIL emoji: '$got'"; FAILED=1;; esac
exit $FAILED
