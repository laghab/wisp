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

surface() { read -r sx sy <<< "$SPACE"; pixel $((sx + W / 6)) $sy; }      # a point on the space bar, away from its label
reset style_theme=wisp style_colors=dark
dark "$(surface)" && check ok "Wisp, dark look: the key surface is dark" || check no "Wisp, dark look: the key surface is dark"
reset style_theme=wisp style_colors=light
dark "$(surface)" && check no "Wisp, light look: the key surface is light" || check ok "Wisp, light look: the key surface is light"

reset style_number_row=true
tap $(key 49); sleep 0.4      # the digit row sits above the letters; its first key is 1
check "$([ "$(field_text)" = "1" ] && echo ok)" "number row: the top-left key types 1"

reset style_theme=wisp style_colors=dark
hold $(K e) 1100          # hold e: the popup opens and a release picks the first extra, 3
check "$([ "$(field_text)" = "3" ] && echo ok)" "long-press popup: holding e and letting go types its first extra (3)"
read -r ex ey <<< "$(K e)"
a input motionevent DOWN $ex $ey; sleep 0.9; a input motionevent MOVE $(pop 101 1); sleep 0.3; a input motionevent UP $(pop 101 1); sleep 0.4   # press and wait for the popup, then slide
check "$(case "$(field_text)" in *é) echo ok;; esac)" "long-press popup: sliding to the next one types é"

# The floating handle and the swap button are not keys, so their places are only known for the Waydroid display.
if [ "$W" = 950 ]; then
reset placement_p_mode=3 placement_p_width=0.8
drag 380 691 380 350 500   # drag the handle up
sleep 0.6
tap 458 545; tap 569 467; sleep 0.5   # h, i at the moved position
check "$([ "$(field_text)" = "hi" ] && echo ok)" "floating: the keyboard moves and types in its new place"
as_app "grep -c 'placement_p_x' files/wisp-settings.properties" | grep -q 1 && check ok "floating: the position is remembered" || check no "floating: the position is remembered"

fi

reset placement_p_mode=1 placement_p_width=0.8
tap $(K q); sleep 0.4      # q, now at the left edge
check "$([ "$(field_text)" = "q" ] && echo ok)" "one-handed (left): keys sit on the left"
tap $((W * 95 / 100)) $R1; sleep 0.4     # where p used to be: nothing
check "$([ "$(field_text)" = "q" ] && echo ok)" "one-handed (left): taps in the empty strip type nothing"
if [ "$W" = 950 ]; then
tap 855 762; sleep 0.8   # the swap button beside the keys moves them to the right
tap 910 812; sleep 0.4
check "$([ "$(field_text)" = "qp" ] && echo ok)" "one-handed: the swap button moves the keyboard to the other side"
fi
reset placement_p_gap=140 style_colors=dark
dark "$(pixel $((W / 2)) $((H * 9 / 10)))" && check ok "raised keyboard: the area below it is filled, not a gap" || check no "raised keyboard: the area below it is filled, not a gap"
as_app "rm -f files/wisp-settings.properties" >/dev/null 2>&1
exit $FAILED
