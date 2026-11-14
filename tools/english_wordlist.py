#!/usr/bin/env python3
"""Re-scores app/src/main/assets/wordlists/en_US.tsv by how often each word is really used.

The list's own numbers are coarse (they rate "ADHD" 91 and "hello" 120), so swipes and corrections could not tell an everyday word from a
rare one. This takes the ranks of the 50,000 most used words in film and TV subtitles (people talking, which is close to how people text)
and writes each word's probability from its rank: 255 for the most used word, 35 less for every ten times further down (rank 1,000 is 150,
rank 10,000 is 115). Words the subtitles do not have keep their old number, capped at 85, so they stay below every word that is in use.
Blocked spellings (-1) are left alone. Running it again changes nothing.

Source: github.com/hermitdave/FrequencyWords, content/2018/en/en_50k.txt (OpenSubtitles 2018; CC BY-SA 4.0).
usage: python3 tools/english_wordlist.py <en_50k.txt>
"""
import math, pathlib, sys

TSV = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/wordlists/en_US.tsv"
UNSEEN_MAX = 85

count = {}
for line in open(sys.argv[1], encoding="utf-8"):
    w, c = line.split()
    count[w] = int(c)

# The subtitles split contractions in two ("don" + "'t", "i" + "'m"): put them back together from their halves.
NT = {"can't": 0.10, "won't": 0.04}                                       # stems that are words themselves share the "'t" count
def uses(word):
    w = word.lower()
    if w in count: return count[w]
    if "'" not in w: return 0
    stem, tail = w.split("'", 1)
    if tail == "t":
        if w in NT: return int(count.get("'t", 0) * NT[w])
        return count.get(stem, 0)                                         # "don", "didn", "isn" only ever come before "'t"
    if tail in ("m", "re", "ll", "ve", "d"): return min(count.get(stem, 0), count.get("'" + tail, 0)) // 3
    if tail == "s": return count.get(stem, 0) // 20
    return 0

ranked = sorted({w for w in count} | set(), key=lambda w: -count[w])
def prob(n):                                                             # from a number of uses, through the rank that number would have
    lo, hi = 0, len(ranked)
    while lo < hi:
        mid = (lo + hi) // 2
        if count[ranked[mid]] > n: lo = mid + 1
        else: hi = mid
    return max(UNSEEN_MAX + 1, round(255 - 35 * math.log10(lo + 1)))

rows = [line.rstrip("\n").split("\t") for line in open(TSV, encoding="utf-8")]
best = {}                                                                 # of two spellings that differ only in capitals, the old favourite keeps the full number
for w, p in rows:
    if int(p) > best.get(w.lower(), (-9, ""))[0]: best[w.lower()] = (int(p), w)
out = []
for w, p in rows:
    old = int(p); n = uses(w)
    if old < 0: new = old
    elif n > 0: new = prob(n) - (0 if best[w.lower()][1] == w else 15)
    else: new = min(old, UNSEEN_MAX)
    out.append(f"{w}\t{new}\n")
TSV.write_text("".join(out), encoding="utf-8")
print(f"{len(out)} words, {sum(1 for w, _ in rows if uses(w) > 0)} scored from use")
