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
Gesture decode time, voice energy, memory after voice unload, APK size with dictionaries, 24-hour dogfood.

### Budget gate (task 10.2), Waydroid, debug build, after the settings, theme and backup work
`tools/bench/budgets.sh <serial>` checks: cold start p95 (116 ms in the quiet run; one run during other load showed 277 ms, still inside 300), key press to commit p99 (2.5 to 5.6 ms vs 16 ms), typing-only memory, no wake locks, final transcript after release (1 to 2 ms), memory after voice unload, and that typing loads no model. Lowering a budget on purpose (`BUDGET_TAP_P99_US=1 BUDGET_COLD_P95_MS=1`) makes it print FAIL lines and exit 1.

Notes:
- Debug-build memory is about 63 MB PSS, of which about 30 MB is the app's un-minified dex held in memory (the Compose settings libraries make the dex bigger). Release builds are minified; measure the release number on the phone (11.2). A release APK is 33 MB for arm64 (budget 45).
- The "cold start" probe starts at the keyboard service and ends at first draw, so it includes the time the test activity takes to ask for the keyboard; it moves with machine load.
- Release build reproducibility (10.3): two clean `assembleRelease` runs gave identical APK contents (`docs/reproducible-builds.md`).
