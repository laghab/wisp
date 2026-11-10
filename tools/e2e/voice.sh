#!/usr/bin/env bash
# On-device checks for voice typing (tasks 6.2-6.7). Needs the Moonshine Tiny model (tools/install-test-model.sh downloads and installs it).
# The debug build replays a recorded sentence as the microphone: "Ask not what your country can do for you. Ask what you can do for your country."
# Usage: tools/e2e/voice.sh <adb-serial>
DEV="${1:?adb serial}"; KEEP_STATE=1; source "$(dirname "$0")/lib.sh"; FAILED=0
HERE="$(dirname "$0")"
SENTENCE="Ask not what your country can do for you"
voice_pids() { a "ps -A | grep ':voice'" | wc -l; }
pss_kb() { a dumpsys meminfo "$1" 2>/dev/null | grep -E "TOTAL PSS:" | head -1 | awk '{print $3}'; }
check() { if [ "$1" = ok ]; then echo "PASS $2"; else echo "FAIL $2"; FAILED=1; fi; }
reset_all() { a am force-stop "$P"; as_app "rm -rf files/personal" >/dev/null 2>&1; }
dictate_hands_free() { tap $MIC; sleep "${1:-9}"; tap $PANEL_DONE; sleep 3; }

"$HERE/../install-test-model.sh" "$DEV" >/dev/null || { echo "FAIL could not install the test model"; exit 1; }
a pm grant "$P" android.permission.RECORD_AUDIO
set_prefs voice_idle_minutes=0

# 1. nothing is loaded by typing
reset_all; launch_field
type_word te
check "$([ "$(voice_pids)" = 0 ] && echo ok)" "typing alone starts no voice process (no model in memory)"
main_before=$(pss_kb "$P")

# 2. tap the mic: hands-free, then the arrow inserts what was said
reset_all; launch_field
dictate_hands_free 9
got="$(field_text)"; echo "field: '$got'"
check "$(case "$got" in *"$SENTENCE"*) echo ok;; esac)" "tap the mic, talk, press the arrow: the transcript is inserted"
adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/voice_after.png"
tap $UNDO_CELL; sleep 0.6
check "$([ -z "$(field_text)" ] && echo ok)" "Undo dictation removes exactly what was inserted"
sleep 2
check "$([ "$(voice_pids)" = 0 ] && echo ok)" "the voice process exited after the idle timeout (no restart)"
sleep 5
check "$([ "$(voice_pids)" = 0 ] && echo ok)" "...and stays gone: the system does not bring it back"
main_after=$(pss_kb "$P"); echo "keyboard process PSS: before ${main_before:-?} KB, after ${main_after:-?} KB"
check "$(awk -v a="${main_after:-0}" -v b="${main_before:-0}" 'BEGIN { exit !(a - b < 15360) }' && echo ok)" "memory returns to within 15 MB of the level before voice"

# 3. hold the mic to talk, let go to insert
reset_all; launch_field
a input swipe $MIC $MIC 8500; sleep 3.5
got="$(field_text)"; echo "field: '$got'"
check "$(case "$got" in *"$SENTENCE"*) echo ok;; esac)" "hold the mic to talk and let go: the transcript is inserted"

# 4. nothing said while the model loads is lost (the load is slowed down by 3 s in debug builds)
set_prefs voice_idle_minutes=0 debug_load_delay_ms=3000
reset_all; launch_field
dictate_hands_free 9
got="$(field_text)"; echo "field: '$got'"
check "$(case "$got" in *"Ask not what your country"*) echo ok;; esac)" "words spoken during a slow model load are not lost (pre-roll)"
set_prefs voice_idle_minutes=0

# 5. cancel throws the dictation away
reset_all; launch_field
tap $MIC; sleep 3; tap $PANEL_CANCEL; sleep 2.5
check "$([ -z "$(field_text)" ] && echo ok)" "cancel inserts nothing"
check "$([ "$(voice_pids)" = 0 ] && echo ok)" "cancel lets the voice process exit"

# 6. password fields never record
reset_all; launch_field --ei inputType 129
tap $MIC; sleep 2
check "$([ "$(voice_pids)" = 0 ] && echo ok)" "the mic does nothing in a password field"

# 7. latency and no audio kept
reset_all; launch_field; a logcat -c
dictate_hands_free 9
fin=$(a logcat -d -s WISP_PROBE | grep voice_finish_ms | tail -1 | sed 's/.*voice_finish_ms=\([0-9]*\).*/\1/')
echo "finish after stop: ${fin:-?} ms (budget 300 ms at p95 on the phone)"
check "$([ -n "$fin" ] && [ "$fin" -lt 1000 ] && echo ok)" "the transcript is ready quickly after the user stops"
audio=$(as_app "find . -type f \( -name '*.wav' -o -name '*.pcm' -o -name '*.m4a' -o -name '*.3gp' -o -name '*.opus' \) ! -name debug_mic.wav | wc -l")
check "$([ "$audio" = 0 ] && echo ok)" "no audio is kept on disk after a dictation"
exit $FAILED
