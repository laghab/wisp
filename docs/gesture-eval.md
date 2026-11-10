# Gesture typing: evaluation and the gate

## How it is measured
- **Synthetic swipes.** `SyntheticGestures` draws a finger path for a word through its letters' key centres. Each letter is hit off-centre by a random miss (Gaussian, `noise` in key widths), the whole swipe is shifted a little, corners are rounded over 0.1-0.5 key widths, and the points are sampled unevenly with jitter. Noise levels: **0.15** careful, **0.30** normal, **0.45** sloppy.
- **Words** are drawn the way people use them: weight grows with the dictionary probability (`exp(0.06 * p)`), words of 3+ letters only (shorter ones are usually tapped).
- **Dictionary:** the shipped English (US) list, 160,715 entries, 143,749 swipeable after folding and de-duplication (`app/src/main/assets/wordlists/en_US.tsv`).
- **Run it:** `cd core && cargo test --release -p wisp-core --test glide_eval -- --nocapture`. The generator and the gate live in `core/wisp-core/tests/glide_eval.rs`; the decoder is `core/wisp-core/src/glide.rs`.
- **Real traces:** `TraceRecorderActivity` (debug build) records how you actually swipe into `traces_en_US.jsonl`. Not yet recorded by a person on the new key grid (see "Open").

## The gate
Thresholds were set from the first baseline of the final algorithm and are enforced by `glide_eval.rs` (results from the Rust decoder, seed 42, 2000 swipes):

| Noise | Top-1 | Top-3 | Result (seed 42, 2000 swipes) |
|---|---|---|---|
| 0.15 careful | at least 93% | at least 98% | **94.1% / 99.0%** |
| 0.30 normal | at least 80% | at least 92% | **81.7% / 93.1%** |
| 0.45 sloppy | at least 50% | at least 65% | **52.5% / 67.0%** |

Speed (host, release build, Rust): decode p50 0.9 ms, p95 4.0 ms; 12+ letter words p95 5.3 ms. Budget: p95 within 50 ms (150 ms for 30+ letters). **The phone's own numbers are still to be measured**.

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
