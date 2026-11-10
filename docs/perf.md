# Performance budgets and how we measure them

Budgets are the release gates from `specs/resource-efficiency` (openspec change `build-wisp-keyboard`). They are provisional until measured on a real phone.

## Devices
| Role | Device | Notes |
|---|---|---|
| Development device (now) | Waydroid, Android 13 (API 33), x86_64, host: Intel Core Ultra 9 285H, 16 threads, 30 GB RAM | Good for correctness and relative regressions. **Much faster than a phone**, so absolute latency and energy numbers here are optimistic. |
| Functional second device | Android Studio emulator, Android 14 (API 34), x86_64 | Used for the spikes. |
| Reference device (to be attached) | the user's phone | Final numbers for latency, memory, energy and the 24-hour dogfood (tasks 2.6, 6.7, 11.2) must come from here. |

## Budgets
| Budget | Target |
|---|---|
| Key press to commit | 99% within 16 ms; press highlight within one frame |
| Dropped frames typing 10 keys/s | under 1% |
| First show after process start | p95 within 300 ms |
| Later shows | p95 within 100 ms |
| Memory, typing only (no voice) | under 80 MB PSS |
| Memory after voice unload | within 15 MB of pre-voice level |
| Gesture decode | p95 within 50 ms (typical word), 150 ms (30+ letters) |
| Final transcript after release | p95 within 300 ms |
| arm64 release APK | at most 45 MB with base dictionaries |
| Hidden keyboard | no wake locks, timers, foreground services or open mic |

## How to measure
- `tools/bench/startup.sh [serial] [runs]`: cold start of the keyboard (debug build). Force-stops the app, focuses a text field, and reads the debug `Probe` timeline (`ime_created`, `view_created`, `first_draw`). Prints median/p95/max in ms and PSS.
- `Probe` (`app/.../Probe.kt`) is compiled out of release builds. Add marks where a new budget needs a timeline.
- Memory: `adb shell dumpsys meminfo app.wisp.keyboard.debug` (PSS). Voice process: the `:voice` suffix.
- Notes: Waydroid freezes its container when no window is open; run `waydroid show-full-ui`. The soft keyboard is only shown next to a hardware keyboard if the system setting `show_ime_with_hard_keyboard` is on; Wisp honors it.

## Results

### Touch path in Rust (task 2.6 of `redesign-wisp-rust-core`), 2026-11-10
| Metric | Result | Budget | Status |
|---|---|---|---|
| Heap allocations in the touch state machine, 50,000 taps with drags and timers (`core/wisp-core/tests/touch_perf.rs`) | 0 | 0 per event | met |
| Touch state machine per event, host, release build (`cargo test --release -p wisp-core --test touch_perf -- --nocapture`) | about 23 ns | well inside 1 ms | met |
| Touch-up to commit call on Waydroid, 300 taps, same machine and day | p50 3.4 to 3.5 ms, p99 5.2 to 5.9 ms (the old Kotlin tracker measured p50 3.8 ms, p99 6.0 ms in the same session) | 99% within 16 ms | met |
| Key surface draw | p50 0.25 ms (old build 0.31 ms) | within one frame | met |

Notes:
- The Waydroid numbers moved up from the 2026-11-06 baseline below on both the old and new builds, so the machine state changed, not the code. Compare only runs from the same session.
- The time is spent in the controller and the commit to the app, not in the Rust call (14 to 50 us per event in the debug build).
- The Gradle debug build compiles Rust unoptimised; the release APK uses `--release`.

### Lexicon, correction and glide in Rust (tasks 3.1 to 3.5 of `redesign-wisp-rust-core`), 2026-11-10
Host numbers, release build (`cargo test --release -p wisp-core --test lex_eval --test glide_eval -- --nocapture`). They replace the HeliBoard engine, which was deleted once these passed.

| Metric | Result | Gate | Status |
|---|---|---|---|
| Old engine's typo cases ("teh", "wrold", "thnks", "recieve" in the top 3, "helo" in the top 5) | all pass; "helo" gives "hello" 3rd (old engine: 4th) | same cases | met |
| 3,000 generated typos of common words (neighbour key, swap, dropped or doubled letter) | top-1 92.4%, top-3 98.0% | top-3 above 80% | met |
| Valid words offered as automatic fixes | 0 of 2,000 | 0 | met |
| Suggestion latency (160k words) | p50 1.0 ms, p99 1.5 ms | old gate: p99 under 8 ms | met |
| Lexicon file / open / first build | 1.3 MB / 6 ms / about 200 ms (once, then cached) | n/a | informational |
| Glide, careful / normal / sloppy noise, top-1 / top-3 | 94.1/99.0, 81.7/93.1, 52.5/67.0 % | 93/98, 80/92, 50/65 % | met |
| Glide decode p50 / p95 (143k swipeable words); 12+ letter words p95 | 0.9 ms / 4.0 ms; 5.3 ms | p95 50 ms; 150 ms for 30+ letters | met on the host, **re-check on the phone** |
| Release APK, arm64 | 30.4 MB (was 33 MB with the C++ engine) | at most 45 MB | met |

On the device (Waydroid): "teh" typed then space gives "the", "wrold" gives "world", backspace restores "wrold"; a hand-drawn swipe over h-e-l-l-o inserts "hello ".

### Voice and memory after the redesign (tasks 7.1 to 7.3 of `redesign-wisp-rust-core`), Waydroid, debug build, 2026-11-10
Dictation of the recorded test sentence through the restyled panel with Moonshine Tiny (`tools/install-test-model.sh`), idle timeout set to 0 minutes.

| Check | Result | Budget | Status |
|---|---|---|---|
| Voice processes while only typing | 0 | model loads only on use | met |
| Voice process while dictating | 1, about 181 MB PSS | n/a | informational |
| After the dictation (idle timeout reached) | 0 voice processes | process exits when idle | met |
| Keyboard process PSS before voice / after the voice process exited | 110.8 MB / 111.0 MB | within 15 MB of the level before | met |
| Words shown live (confirmed solid, last three grey), inserted on Done | yes, full sentence inserted | live transcript | met |
| Keyboard process PSS, typing only, debug build | 98 MB (109 MB before the swipe index was rebuilt in a few big buffers; native heap 40 to 30 MB) | under 80 MB | **over in a debug build; measure the release build on the phone** |

Notes:
- The debug number includes about 30 MB of un-minified dex and the Rust lexicon (about 10 MB: word list, next-word pairs, swipe index). A release build is minified and was 33 MB smaller on disk; the release PSS must be measured on the phone (task 10.3).
- The swipe index is built once per language start from the lexicon file; building it with sort-and-dedupe in three large buffers instead of one allocation per word kept 6 MB resident instead of 19 MB (host measurement, `core/wisp-core/tests/mem_probe.rs`).

### AI rewrite (tasks 8.1 to 8.3 of `redesign-wisp-rust-core`), Waydroid x86_64, debug build, Qwen 2.5 0.5B (4-bit), 2026-11-10
| Check | Result | Status |
|---|---|---|
| Voice process while only typing | none | met |
| Rewrite of "hey sorry cant make it thurs something came up at work" (Polish) | "Hey, sorry I can't make it on Thurs. Something came up at work." in 2.7 to 2.8 s, text streaming into the card | met |
| Field while the rewrite runs, and after closing the panel without choosing | unchanged | met |
| Replace draft | the selected sentence is replaced; the field is otherwise untouched | met |
| Voice process while rewriting | about 615 MB PSS (the 491 MB model is memory-mapped) | informational |
| After the rewrite with the idle timeout at 0 | process gone (shared idle unload with the speech model) | met |
| No model installed | the panel explains and offers Open Models; the rewrite button never fails silently | met |
| Engine size | `libwisp_llm.so` 4.8 MB (arm64), 4.8 MB (arm64 with dot-product), 5.3 MB (x86_64), llama.cpp v0.9.0 pinned by commit and by SHA-256 | informational |

Notes:
- Speed on a phone is not measured: this is a desktop CPU. The dot-product build is picked on arm64 phones that report `asimddp`; others use the plain arm64 build. 32-bit ARM is not built (llama.cpp no longer builds for it) and the panel reports rewrite as unavailable there.
- Gemma 3 1B and Qwen 2.5 1.5B were not run here (806 MB and 1.1 GB downloads); the same code path loads any GGUF the catalogue lists.

### Budget gate (task 10.3 of `redesign-wisp-rust-core`), Waydroid, debug build, 2026-11-11
`tools/bench/budgets.sh`: cold start p95 116 ms (budget 300), typing-only PSS 80.9 MB (budget 80 MB, 1 MB over in a debug build: measure the release build on the phone), key press to commit p99 2.2 ms (16), wake locks 0, typing loads no model, memory after voice unload within budget. **"Final transcript after release" gave no measurement**: the script taps old key coordinates; it is part of task 10.6. Phone numbers (latency, memory, energy, APK) are still to be taken on the reference phone.

### Tap-typing core (task 2.6), Waydroid, debug build, 2026-11-06
| Metric | Result | Budget | Status |
|---|---|---|---|
| Touch-up to commit call (200 taps, `tools/bench/typing.sh`) | p50 1.1 ms, p95 1.7 ms, p99 2.4 ms | 99% within 16 ms | met |
| Key surface draw (`onDraw`, 100 draws) | p50 0.10 ms, p99 0.14 ms | within one frame | met |
| IME created to first draw, 10 cold runs (`tools/bench/startup.sh`) | median 85 ms, p95 111 ms | p95 300 ms | met |
| PSS with keyboard shown (plus the test activity in the same process) | about 39 MB | under 80 MB | met |
| Dropped frames while typing (gfxinfo, 3 runs of 200-300 taps) | 1.4%, 1.3%, 0.0% | under 1% | **inconclusive** |

Notes:
- Waydroid runs on a 16-thread desktop CPU, so absolute times are far better than a phone will show. Treat these as regression baselines, not as proof for the phone.
- Frame timing is quantised to the 60 Hz vsync on Waydroid (every frame 16-18 ms, including idle), and the janky-frame count swung from 0 to 1.4% between identical runs with no missed vsync, slow UI thread or slow draw reported. That points at the emulated GPU path rather than the keyboard, but it is not proof. The frame budget must be confirmed on the phone (task 11.2).
- "Touch-up to commit call" measures the keyboard's own handling (touch event to `commitText` returning). Human hold time is excluded.
- The very first tap after launch is swallowed by window focus on Waydroid; the e2e scripts spend one tap on the text area to absorb it.

### Voice typing (tasks 6.2-6.7), Waydroid, debug build, Moonshine Tiny
| Metric | Result | Budget | Status |
|---|---|---|---|
| Voice process while the model is loaded | about 96 MB native heap (about 125 MB PSS) | n/a | informational |
| Model load (Moonshine Tiny) | 0.57 to 0.62 s | n/a | informational |
| Keyboard process memory before voice / after the voice process exited | 52.7 MB / 51.8 MB | within 15 MB | met |
| Process exits after the idle timeout and is not restarted | yes (checked 7 s later) | no idle work | met |
| Typing alone loads no model | no `:voice` process exists | load only on use | met |
| Finish after stopping (final text ready) | 56 to 194 ms (stopped mid-sentence), 2 ms when speech had ended | p95 300 ms on the phone | met on this CPU; **re-check on the phone** |
| Words said during a 3 s slow model load | all kept (pre-roll) | no lost speech | met |
| Audio left on disk after a dictation | none | none | met |
Voice energy per minute of dictation cannot be measured on Waydroid (no battery); it is measured on the phone in task 11.2.

### Not yet measured
Gesture decode time on the phone, voice energy, memory after voice unload, APK size with dictionaries, 24-hour dogfood.

### Budget gate (task 10.2), Waydroid, debug build, after the settings, theme and backup work
`tools/bench/budgets.sh <serial>` checks: cold start p95 (116 ms in the quiet run; one run during other load showed 277 ms, still inside 300), key press to commit p99 (2.5 to 5.6 ms vs 16 ms), typing-only memory, no wake locks, final transcript after release (1 to 2 ms), memory after voice unload, and that typing loads no model. Lowering a budget on purpose (`BUDGET_TAP_P99_US=1 BUDGET_COLD_P95_MS=1`) makes it print FAIL lines and exit 1.

Notes:
- Debug-build memory is about 63 MB PSS, of which about 30 MB is the app's un-minified dex held in memory (the Compose settings libraries make the dex bigger). Release builds are minified; measure the release number on the phone (11.2). A release APK is 33 MB for arm64 (budget 45).
- The "cold start" probe starts at the keyboard service and ends at first draw, so it includes the time the test activity takes to ask for the keyboard; it moves with machine load.
- Release build reproducibility (10.3): two clean `assembleRelease` runs gave identical APK contents (`docs/reproducible-builds.md`).
