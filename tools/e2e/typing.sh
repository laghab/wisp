#!/usr/bin/env bash
# On-device checks for tap typing (task 2.2). Usage: tools/e2e/typing.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
start_field

# letters + shift once
tap $SHIFT; tap $(K i); tap $(K i)                   # i, i   -> "Ii"
expect "shift once" "Ii"

# caps lock: double-tap shift
tap $SHIFT; tap $SHIFT; tap $(K q); tap $(K w)         # q w -> "QW"
tap $SHIFT                                                       # lock off
expect "caps lock" "IiQW"

# backspace tap
tap $BKSP
expect "backspace tap" "IiQ"

# held backspace clears the rest (many repeats, then word delete)
hold $BKSP 2500
expect "backspace hold" ""

# cursor slide: type "ab", drag left on space, type "x" -> "axb"
tap $(K a); tap $(K s)                                  # sets text to "as" (a, s)
drag 620 $R4 505 $R4 300                                        # slide left ~115px (about 8 steps, clamped by text start)
tap $(K r)                                                   # r
expect "cursor slide (r inserted before the caret position, not at the end)" "ras"
exit $FAILED
