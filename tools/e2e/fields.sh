#!/usr/bin/env bash
# On-device checks for field-type adaptation (task 2.3). Usage: tools/e2e/fields.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
field() { launch_field --ei inputType "$1" --ei imeOptions "${2:-0}"; }   # field <inputType> [imeOptions]
shot() { adb -s "$DEV" exec-out screencap -p > "${SHOTS:-/tmp}/$1.png"; }

# number pad: 4 columns (x 122 357 593 828), rows y 829 900 970 1045
field 8194   # TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_DECIMAL
tap 122 829; tap 357 829; tap 593 1045; tap 357 900                 # 1 2 . 5
expect "number field uses the number pad" "12.5"; shot field_number

# email: bottom row has '@' where comma normally is
field 33
tap $(KX 0) 900; tap 378 1045
expect "email field has an @ key" "a@"

# url: '/' key
field 17
tap $(KX 0) 900; tap 378 1045
expect "url field has a / key" "a/"

# password: text is typed, never suggested; the dump masks it
field 129
tap $(KX 0) 900; tap $(KX 1) 900
got="$(field_text)"; [ ${#got} -eq 2 ] && echo "PASS password field accepts typing ('$got')" || { echo "FAIL password: '$got'"; FAILED=1; }

# enter key label follows the field action (3 = search): screenshot for the visual check
field 1 3; shot field_search
exit $FAILED
