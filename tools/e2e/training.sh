#!/usr/bin/env bash
# On-device check for gesture training (task 5.5). Usage: tools/e2e/training.sh <adb-serial>
DEV="${1:?adb serial}"; KEEP_STATE=1; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
swipe() { python3 "$HERE/swipe.py" "$@" > /tmp/swipe.$$.sh; adb -s "$DEV" push /tmp/swipe.$$.sh /data/local/tmp/swipe.sh >/dev/null; a sh /data/local/tmp/swipe.sh; sleep 0.7; }
prompt_word() {
  a uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  a cat /sdcard/u.xml | grep -o 'text="[a-z]\{3,9\}"' | head -1 | sed 's/text="//; s/"$//'
}
style() { as_app "cat files/personal/gesture_style.txt" 2>/dev/null; }

a am force-stop "$P"; as_app "rm -f files/personal/gesture_style.txt files/personal/training_en_US.tsv" >/dev/null 2>&1
a ime set com.android.inputmethod.latin/.LatinIME >/dev/null
a am start -n "$P/app.wisp.keyboard.PracticeForTests" >/dev/null; sleep 7

done_n=0; ok=0
for i in $(seq 1 20); do
  w="$(prompt_word)"; [ -z "$w" ] && { echo "WARN no word on screen at step $i"; sleep 1; w="$(prompt_word)"; }
  [ -z "$w" ] && break
  swipe "$w" 3 16 -14                                    # every swipe lands a little right of and above the ideal line: a habit
  done_n=$((done_n + 1))
done
[ "$done_n" -ge 18 ] && echo "PASS a practice session went through ($done_n swipes)" || { echo "FAIL only $done_n swipes"; FAILED=1; }
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/training_done.png"
st="$(style)"; echo "style file: $st"
read -r bx by ratio spread ev <<< "$st"
awk -v bx="$bx" -v by="$by" 'BEGIN { exit !(bx > 0.05 && by < -0.05) }' && echo "PASS the style profile learned the habit (offset $bx, $by key widths)" || { echo "FAIL style did not pick up the habit: '$st'"; FAILED=1; }
exit $FAILED
