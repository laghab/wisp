#!/usr/bin/env bash
# Task 7.7: with no speech model installed, typing works and the mic leads to model setup. Restores the model afterwards.
# Usage: tools/e2e/nomodel.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
check() { if [ "$1" = ok ]; then echo "PASS $2"; else echo "FAIL $2"; FAILED=1; fi; }
a pm grant "$P" android.permission.RECORD_AUDIO
as_app "[ -d no_backup/models ] && mv no_backup/models no_backup/models.off" >/dev/null 2>&1
trap 'as_app "[ -d no_backup/models.off ] && rm -rf no_backup/models && mv no_backup/models.off no_backup/models" >/dev/null 2>&1' EXIT
launch_field
type_word te   # letters typed with no model installed
check "$(case "$(field_text)" in te*) echo ok;; esac)" "typing works with no model installed"
tap $MIC; sleep 1.5
check "$(a dumpsys activity activities | grep -q 'topResumedActivity.*SettingsActivity' && echo no || echo ok)" "opening the panel does not leave the keyboard by itself"
tap $PANEL_MIC; sleep 4
check "$(a dumpsys activity activities | grep -q 'topResumedActivity.*SettingsActivity' && echo ok)" "tapping the mic with no model opens the model setup screen"
exit $FAILED
