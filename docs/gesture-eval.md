# Gesture typing: evaluation and the gate

## How it is measured
- **Synthetic swipes.** `SyntheticGestures` draws a finger path for a word through its letters' key centres. Each letter is hit off-centre by a random miss (Gaussian, `noise` in key widths), the whole swipe is shifted a little, corners are rounded over 0.1-0.5 key widths, and the points are sampled unevenly with jitter. Noise levels: **0.15** careful, **0.30** normal, **0.45** sloppy.
- **Words** are drawn the way people use them: weight grows with the dictionary probability (`exp(0.06 * p)`), words of 3+ letters only (shorter ones are usually tapped).
- **Dictionary:** the shipped English (US) list, 160,715 entries, 125,999 swipeable after folding and de-duplication (`eval/words_en_US.tsv`, created by `tools/export-wordlists.sh`).
- **Run it:** `./gradlew testDebugUnitTest --tests '*GestureGateTest*'` (needs `eval/words_en_US.tsv`). On a device: `tools/e2e/selftest.sh <serial> gesture`.
- **Real traces:** `TraceRecorderActivity` (debug build) records how you actually swipe; `GestureRecordedTraceTest` replays `eval/traces_en_US.jsonl`. Not yet recorded by a person (see "Open").

## The gate (task 4.6)
Thresholds were set from the first baseline of the final algorithm and are enforced by `GestureGateTest`:

| Noise | Top-1 | Top-3 | Result (seed 42, 2000 swipes) |
|---|---|---|---|
| 0.15 careful | at least 93% | at least 98% | **95.3% / 99.3%** |
| 0.30 normal | at least 80% | at least 92% | **82.6% / 94.2%** |
| 0.45 sloppy | at least 50% | at least 65% | **55.8% / 68.7%** (top-5 73.0%) |

Speed (desktop JVM, so optimistic for a phone): decode median 1.1 ms, p95 5.9 ms, p99 9.7 ms; 12+ letter words p95 7 ms. Budget: p95 within 50 ms (150 ms for 30+ letters). On the device runtime (Waydroid, ART, same desktop CPU): median 4.9 ms, p95 15 ms. **The phone's own numbers are still to be measured** (task 11.2); if they exceed the budget the decoder moves to C++ (`design.md` D6).

## How accuracy was reached (log)
| Step | Normal-noise top-1 / top-3 |
|---|---|
| Arc-length point-by-point comparison, all dictionary words, no demotion | 39% / 58% |
| Dynamic time warping instead of point-by-point | 56% / 74% (but slow: p95 70 ms) |
| Demote acronyms and names (the dictionary rates "ADHD" 91 vs "hello" 120) | included above |
| More realistic corner cutting in the synthetic swipes (the old generator missed letters by a full key) | 77% / 89% |
| Letter-visit pruning and cost; coordinate-descent tuning of the weights | 82.6% / 94.2% |
Speed was recovered with exact pruning (visit check from a per-swipe table, lower-bound skip, early abandoning of the alignment): p95 70 ms to 5.9 ms with identical accuracy.

## Confidence: when a swipe is inserted on its own
Absolute cost cannot tell right from wrong (both about -0.1 to 0.1). Two signals can:
- **Margin** between the best and second word: wrong best words win by a median 0.02, right ones by about 0.10. Rule: margin at least 0.015. At normal noise this auto-inserts 90% of swipes with 86% right (inserting everything gives 83%).
- **Path fit** (cost without the frequency bonus) at most 0.32: real words at normal noise have a median fit of 0.18 (90% under 0.30), while absurd paths and out-of-dictionary words are mostly worse. This catches "nothing fits": a swipe of `q-x-z-v` used to insert "avg".
Otherwise nothing is inserted; the strip offers the literal letters the path crossed and the best words.

## Open
- Real human traces (record about 40 words with the recorder tool on the phone), then re-check the thresholds above and the weights in `GestureDecoder`.
- A bigram (previous word) bonus: the decoder already receives the previous word; the engine's bigram data looked sparse, so it is unused so far.
- Names the dictionary lacks are offered, not typed: learning them is group 5.
