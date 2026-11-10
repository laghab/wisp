# Performance budgets and how we measure them

Budgets are the release gates. Until they are measured on a real phone they are provisional.

## Devices
| Role | Device | Notes |
|---|---|---|
| Development device | Waydroid, Android 13 (API 33), x86_64, display forced to 950x1158 at density 216, host Intel Core Ultra 9 285H, 16 threads | Good for correctness and regressions. **Much faster than a phone**, so absolute latency and energy numbers are optimistic. |
| Reference device (to be attached) | the owner's phone | Final numbers for latency, memory, energy and a 24-hour daily-use run must come from here. |

## Budgets
| Budget | Target |
|---|---|
| Key press to commit | 99% within 16 ms; press highlight within one frame |
| Dropped frames typing 10 keys/s | under 1% |
| First show after process start | p95 within 300 ms |
| Later shows | p95 within 100 ms |
| Memory, typing only (no voice) | under 80 MB PSS |
| Memory after voice unload | within 15 MB of the pre-voice level |
| Gesture decode | p95 within 50 ms (typical word), 150 ms (30+ letters) |
| Final transcript after release | p95 within 300 ms |
| arm64 release APK | at most 45 MB with the word lists |
| Hidden keyboard | no wake locks, timers, foreground services or open mic |

## How to measure
- `tools/bench/budgets.sh <serial>` runs the gate: cold start, press-to-commit, wake locks, voice memory and finish time, gesture decode and APK size. Lowering a budget on purpose (`BUDGET_TAP_P99_US=1 BUDGET_COLD_P95_MS=1`) makes it print FAIL lines and exit 1.
- `tools/bench/typing.sh`, `startup.sh`, `apk-size.sh` run single checks. `Probe` (compiled out of release builds) writes the timeline marks they read.
- Host (Rust): `cargo test --release -p wisp-core --test touch_perf --test lex_eval --test glide_eval -- --nocapture`.
- Memory: `adb shell dumpsys meminfo app.wisp.keyboard.debug` (PSS); the voice process has the `:voice` suffix.
- On-device checks: `tools/e2e/all.sh <serial>`. Key positions for them live in one place (`tools/e2e/swipe.py` for letter keys, `tools/e2e/lib.sh` for the rest).
- Waydroid freezes its container when no window is open: keep `waydroid show-full-ui` running. The soft keyboard shows next to a hardware keyboard only if `show_ime_with_hard_keyboard` is on; Wisp honours it. The first tap after launch is swallowed by window focus, so the scripts spend one tap on the text area.

## Latest results (Waydroid, debug build, 2026-11-11)
| Metric | Result | Budget | Status |
|---|---|---|---|
| Press to commit, 200 taps | p99 2.2 ms | 16 ms | met |
| Touch state machine (host), 50,000 taps | 0 allocations, about 23 ns per event | 0 allocations | met |
| Key surface draw | p50 0.25 ms | one frame | met |
| Cold start (service to first draw) | p95 116 ms | 300 ms | met |
| Typing-only PSS, release build (settings UI in the same process) | 46 MB | 80 MB | met |
| Typing-only PSS, debug build | 80.9 to 88.2 MB across runs | 80 MB | over in debug only: about 30 MB is un-minified dex |
| Wake locks held | 0 | 0 | met |
| Voice processes while only typing | 0 | model loads only on use | met |
| Keyboard PSS before / after a dictation | 87.6 MB / 77.6 MB | within 15 MB | met |
| Voice process while dictating | about 181 MB PSS | n/a | informational |
| Final transcript after release | 2 ms | 300 ms | met on this CPU, re-check on the phone |
| Words said during a 3 s slow model load | all kept (pre-roll) | none lost | met |
| Audio left on disk after dictation | none | none | met |
| Dropped frames typing | 0 to 1.4% across runs | under 1% | **inconclusive**: Waydroid quantises to 60 Hz vsync with no missed vsync reported; confirm on the phone |
| arm64 release APK (speech and rewrite engines included) | 38.4 MB | 45 MB | met |
| Two clean release builds | identical APK contents | reproducible | met (`docs/reproducible-builds.md`) |

## Typing core (host, release build)
| Metric | Result | Gate |
|---|---|---|
| 3,000 generated typos of common words (neighbour key, swap, dropped or doubled letter) | top-1 92.4%, top-3 98.0% | top-3 above 80% |
| Valid words offered as automatic fixes | 0 of 2,000 | 0 |
| Suggestion latency, 160k words | p50 1.0 ms, p99 1.5 ms | p99 under 8 ms |
| Glide, careful / normal / sloppy noise, top-1 / top-3 | 94.1/99.0, 81.7/93.1, 52.5/67.0 % | 93/98, 80/92, 50/65 % |
| Glide decode, 143k swipeable words | p50 0.9 ms, p95 4.0 ms; 12+ letters p95 5.3 ms | p95 50 ms; 150 ms for 30+ letters |
| Lexicon file / open / first build | 1.3 MB / 6 ms / about 200 ms once, then cached | informational |

The swipe index is built once per language start from the lexicon file, with sort-and-dedupe in three large buffers (6 MB resident instead of 19 MB; `core/wisp-core/tests/mem_probe.rs`). `docs/gesture-eval.md` describes the glide gate.

## AI rewrite (Waydroid x86_64, Qwen 2.5 0.5B 4-bit)
| Check | Result |
|---|---|
| Polish "hey sorry cant make it thurs something came up at work" | "Hey, sorry I can't make it on Thurs. Something came up at work." in 2.7 to 2.8 s, streaming into the card |
| Field while the rewrite runs, and after closing without choosing | unchanged |
| Replace | the selected sentence is replaced and nothing else |
| Voice process while rewriting | about 615 MB PSS (the 491 MB model is memory-mapped); gone after the idle timeout, shared with the speech model |
| No model installed | the panel explains and offers Open Models |
| Engine size | `libwisp_llm.so` 4.8 MB arm64, 4.8 MB arm64 with dot-product, 5.3 MB x86_64; llama.cpp v0.9.0 pinned by commit and SHA-256 |

The dot-product build is picked on arm64 phones that report `asimddp`. 32-bit ARM is not built (llama.cpp no longer builds for it) and the panel reports rewrite as unavailable there. Gemma 3 1B and Qwen 2.5 1.5B were not run; the same code path loads any GGUF the catalogue lists.

## Not yet measured
On the phone: press-to-commit and frame timing, memory, gesture decode, voice energy per minute, rewrite speed, battery and a 24-hour daily-use run.
