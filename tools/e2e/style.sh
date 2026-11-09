#!/usr/bin/env bash
# On-device checks for the look-and-feel options (task group 8): theme, number row, long-press popups, floating mode, one-handed.
# Usage: tools/e2e/style.sh <adb-serial>
DEV="${1:?adb serial}"; KEEP_STATE=1; source "$(dirname "$0")/lib.sh"; FAILED=0
check() { if [ "$1" = ok ]; then echo "PASS $2"; else echo "FAIL $2"; FAILED=1; fi; }
# colour of one pixel of the screen: pixel X Y -> "r g b"
pixel() { adb -s "$DEV" exec-out screencap | python3 -c "
import sys,struct
d=sys.stdin.buffer.read(); w,h,f=struct.unpack('<III',d[:12]); x,y=$1,$2; o=12+(y*w+x)*4; print(d[o],d[o+1],d[o+2])"; }
reset() { as_app "rm -f files/wisp-settings.properties" >/dev/null 2>&1; set_prefs "$@"; launch_field; }
dark() { read -r r g b <<<"$1"; [ $((r+g+b)) -lt 300 ]; }

reset style_theme=dark
dark "$(pixel 475 1085)" && check ok "dark theme: the key surface is dark" || check no "dark theme: the key surface is dark"
reset style_theme=light
dark "$(pixel 475 1085)" && check no "light theme: the key surface is light" || check ok "light theme: the key surface is light"
reset style_theme=system; a cmd uimode night yes >/dev/null; sleep 0.5; launch_field
dark "$(pixel 475 1085)" && check ok "follow system: dark when the phone is dark" || check no "follow system: dark when the phone is dark"
a cmd uimode night no >/dev/null; sleep 2.5
dark "$(pixel 475 1085)" && check no "follow system: switches to light without a restart" || check ok "follow system: switches to light without a restart"

reset style_number_row=true
tap 51 757; sleep 0.4      # the digit row sits above the letters; its first key is 1
check "$([ "$(field_text)" = "1" ] && echo ok)" "number row: the top-left key types 1"

reset style_theme=dark
hold 239 829 1100          # hold e: the popup opens and a release picks the first extra, 3
check "$([ "$(field_text)" = "3" ] && echo ok)" "long-press popup: holding e and letting go types its first extra (3)"
drag 239 829 330 829 1200
check "$(case "$(field_text)" in *é) echo ok;; esac)" "long-press popup: sliding to the next one types é"

reset style_floating=true
drag 475 710 300 350 500   # drag the handle up and left
sleep 0.6
tap 455 553; tap 567 481; sleep 0.5   # h, i at the moved position
check "$([ "$(field_text)" = "hi" ] && echo ok)" "floating: the keyboard moves and types in its new place"
as_app "grep -c float_ files/wisp-settings.properties" | grep -q 2 && check ok "floating: the position is remembered" || check no "floating: the position is remembered"

reset style_one_handed=left
tap 42 829; sleep 0.4      # q, now at the left edge
check "$([ "$(field_text)" = "q" ] && echo ok)" "one-handed (left): keys sit on the left"
tap 900 829; sleep 0.4     # where p used to be: nothing
check "$([ "$(field_text)" = "q" ] && echo ok)" "one-handed (left): taps in the empty strip type nothing"
tap 853 857; sleep 0.8   # the swap button, at the top of the empty strip, moves the keys to the right
tap 900 829; sleep 0.4
check "$([ "$(field_text)" = "qp" ] && echo ok)" "one-handed: the swap button moves the keyboard to the other side"
as_app "rm -f files/wisp-settings.properties" >/dev/null 2>&1
exit $FAILED
