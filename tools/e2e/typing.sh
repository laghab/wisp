#!/usr/bin/env bash
# On-device checks for tap typing (task 2.2). Usage: tools/e2e/typing.sh <adb-serial>
DEV="${1:?adb serial}"; source "$(dirname "$0")/lib.sh"; FAILED=0
start_field

# letters + shift once
tap $SHIFT; tap $(KX 7) 829; tap $(KX 7) 829                   # i, i   -> "Ii"
expect "shift once" "Ii"

# caps lock: double-tap shift
tap $SHIFT; tap $SHIFT; tap $(KX 0) 829; tap $(KX 1) 829         # q w -> "QW"
tap $SHIFT                                                       # lock off
expect "caps lock" "IiQW"

# backspace tap
tap $BKSP
expect "backspace tap" "IiQ"

# held backspace clears the rest (many repeats, then word delete)
hold $BKSP 2500
expect "backspace hold" ""

# cursor slide: type "ab", drag left on space, type "x" -> "axb"
tap $(KX 0) 900; tap $(KX 1) 900                                  # sets text to "as" (a, s)
drag 610 1045 495 1045 300                                        # slide left ~115px (about 8 steps, clamped by text start)
tap $(KX 3) 829                                                   # r
expect "cursor slide (r inserted before the caret position, not at the end)" "ras"
exit $FAILED
