#!/usr/bin/env python3
"""Prints a shell script that swipes a word on the English layout of the 950px-wide Waydroid display.

usage: swipe.py <word> [wobble_px] [bias_x_px] [bias_y_px]    (wobble adds a small deterministic offset so the path is not perfectly straight; the bias shifts the whole swipe, like a personal habit)
"""
import math, sys

W, PAD = 950.0, 3.9
U = (W - 2 * PAD) / 10
ROW_Y = [829, 901, 973]

def center(ch):
    for r, row in enumerate(("qwertyuiop", "asdfghjkl", "zxcvbnm")):
        if ch in row:
            i = row.index(ch)
            x = PAD + U * (i + (0.5, 1.0, 2.0)[r] + (0 if r == 0 else 0))
            if r == 0: x = PAD + U * (i + 0.5)
            return x, ROW_Y[r]
    raise SystemExit(f"no key for {ch!r}")

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
