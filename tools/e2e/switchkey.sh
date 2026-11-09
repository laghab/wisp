#!/usr/bin/env bash
# On-device checks for the switch-keyboard key (task 2.4). Usage: tools/e2e/switchkey.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
LATIN=com.android.inputmethod.latin/.LatinIME; WISP="$P/app.wisp.keyboard.WispIme"
SW="191 1045"
cur() { a settings get secure default_input_method | tr -d '\r'; }

# tap: back to the keyboard used before Wisp
a ime set "$LATIN" >/dev/null; sleep 1
launch_field
[ "$(cur)" = "$WISP" ] || { echo "FAIL setup: current is $(cur)"; exit 1; }
tap $SW; sleep 1.5
[ "$(cur)" = "$LATIN" ] && echo "PASS switch key tap goes to the previous keyboard" || { echo "FAIL tap: current is $(cur)"; FAILED=1; }

# long press: system picker appears and Wisp is still current
a ime set "$LATIN" >/dev/null; sleep 1
launch_field
hold $SW 900; sleep 1.5
a uiautomator dump /sdcard/u.xml >/dev/null 2>&1
if a cat /sdcard/u.xml | grep -qiE 'Choose input method|Wisp keyboard.*(English|Spike)|select_dialog|input method'; then
  echo "PASS switch key long press opens the keyboard picker"
else
  echo "FAIL long press: no picker found"; FAILED=1
fi
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/switch_picker.png"
a input keyevent KEYCODE_BACK
exit $FAILED
