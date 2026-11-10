#!/usr/bin/env python3
"""Prints a shell script that swipes a word on the English layout of the Waydroid display, and is the home of the letter-key positions used by lib.sh.

usage: swipe.py <word> [wobble_px] [bias_x_px] [bias_y_px]    (wobble adds a small deterministic offset so the path is not perfectly straight; the bias shifts the whole swipe, like a personal habit)
"""
import math, sys

# The one table of letter-key positions for the 950x1158 Waydroid display (density 216): first key x, pitch, row y.
ROWS = (("qwertyuiop", 48, 94.7, 812), ("asdfghjkl", 76, 99.7, 890), ("zxcvbnm", 188, 95.5, 969))

def center(ch):
    for keys, x0, pitch, y in ROWS:
        if ch in keys:
            return x0 + pitch * keys.index(ch), y
    raise SystemExit(f"no key for {ch!r}")

if sys.argv[1] == "--key":      # lib.sh asks for a key position with: swipe.py --key <letter>
    print("%d %d" % center(sys.argv[2])); raise SystemExit
if sys.argv[1] == "--rows":     # ... and for the three letter-row heights
    print(*(r[3] for r in ROWS)); raise SystemExit

word = sys.argv[1]
wobble = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
bias_x = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
bias_y = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
pts = []
prev = None
for ch in word:
    if ch == prev: continue
    prev = ch
    pts.append(center(ch))
path = [pts[0]]
for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
    n = max(1, int(math.hypot(x1 - x0, y1 - y0) / 40))
    for s in range(1, n + 1):
        path.append((x0 + (x1 - x0) * s / n, y0 + (y1 - y0) * s / n))
out = []
for i, (x, y) in enumerate(path):
    wx = wobble * math.sin(i * 1.7); wy = wobble * math.cos(i * 2.3)
    verb = "DOWN" if i == 0 else "MOVE"
    out.append(f"input motionevent {verb} {x + wx + bias_x:.0f} {y + wy + bias_y:.0f}")
x, y = path[-1]
out.append(f"input motionevent UP {x + bias_x:.0f} {y + bias_y:.0f}")
print("\n".join(out))
