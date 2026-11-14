# Gesture typing: evaluation and the gate

## How it is measured
- **Synthetic swipes.** `SyntheticGestures` draws a finger path for a word through its letters' key centres. Each letter is hit off-centre by a random miss (Gaussian, `noise` in key widths), the whole swipe is shifted a little, corners are rounded over 0.1-0.5 key widths, and the points are sampled unevenly with jitter. Noise levels: **0.15** careful, **0.30** normal, **0.45** sloppy.
- **Words** are drawn the way people use them: weight grows with the dictionary probability (`exp(0.06 * p)`), words of 3+ letters only (shorter ones are usually tapped). The probabilities come from real use (below), so the draw is close to everyday text: mostly common words, a rare one now and then.
- **Dictionary:** the shipped English (US) list, 160,715 entries, 143,749 swipeable after folding and de-duplication (`app/src/main/assets/wordlists/en_US.tsv`). Its probabilities are set by `tools/english_wordlist.py` from how often each word is used in film and TV subtitles: 255 for the most used word, 35 less for every ten times further down the ranking; words the subtitles lack are capped at 85. The upstream numbers were too coarse to tell an everyday word from a rare one ("ADHD" 91, "hello" 120).
- **Run it:** `cd core && cargo test --release -p wisp-core --test glide_eval -- --nocapture`. The generator and the gate live in `core/wisp-core/tests/glide_eval.rs`; the decoder is `core/wisp-core/src/glide.rs`. `WISP_WORDS=<list>` decodes against another word list on the same swipes, and `WISP_PRIOR=<weight>` tries another weight for common words.
- **Real swipes:** `TraceRecorderActivity` (debug build: `adb shell am start -n app.wisp.keyboard.debug/app.wisp.keyboard.TraceRecorderActivity`) asks for 70 everyday words and records each swipe with the key positions it was made on. Pull the file to `eval/traces_en_US.jsonl` (`adb pull /sdcard/Android/data/app.wisp.keyboard.debug/files/traces_en_US.jsonl eval/`) and the `real_swipes` test replays it.

## The gate
Thresholds were set from the first baseline of the final algorithm and are enforced by `glide_eval.rs` (results from the Rust decoder, seed 42, 2000 swipes):

| Noise | Top-1 | Top-3 | Result (seed 42, 2000 swipes) | Same swipes, upstream probabilities |
|---|---|---|---|---|
| 0.15 careful | at least 91% | at least 98% | **92.2% / 99.0%** | 87.7% / 96.8% |
| 0.30 normal | at least 78.5% | at least 91% | **79.9% / 92.3%** | 74.7% / 89.0% |
| 0.45 sloppy | at least 52% | at least 67% | **53.6% / 68.8%** | 48.1% / 63.8% |

With the word before known, on the word list's own pairs at normal noise: 84.8% alone, **93.2%** in context (`the_word_before_helps`).

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

## What ranks a word
- **How common it is** (`prior_weight` 0.40 over the 0 to 255 probability). Weights from 0.4 to 1.3 were tried on the list above; 0.4 is best at careful and normal noise.
- **The word before** (`context_weight` 0.08): a word that often follows the previous one, by the word list's pair table or by this person's own learned pairs (which count for more), is favoured after decoding.
- **A second look:** a swipe that matches nothing (it started or ended well off its keys) is decoded again with wider limits, so a real swipe always gets an answer.
- Leaving rare words out of the index, or charging them a flat penalty, was measured and dropped: both read fewer swipes right than the frequency bonus alone.

## Open
- Real swipes from a person on the phone, then re-check the weights above and the confidence thresholds.
- The pair table is thin (about three followers for each of 7,400 words). A fuller one needs a text corpus with a clear licence.
