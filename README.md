# Wisp

A private, fast Android keyboard with built-in swipe typing and voice typing that runs on your phone.

- **Swipe typing that works from the first minute.** No downloads, no Google library. It learns your words and how you swipe, and has a practice mode that takes a few minutes a day.
- **Voice typing on the device.** Words appear as you speak. The speech model is loaded only while you dictate and freed after a few idle minutes. Several models to choose from, with honest notes and a speed test on your own phone.
- **English and Hinglish** (Hindi in Latin letters), with a switch key that is always there.
- **Yours to shape.** Themes, accent colours, sizes, one-handed and split modes, number row, editable layouts and toolbar.
- **Private.** No account, no analytics, no ads. Everything Wisp learns stays in a file on your phone that you can see, back up, or erase.

Project page and case study: https://raghavdaksh.com/projects/wisp-keyboard

Licence: GPL-3.0 (`LICENSE`); reused code and data are listed in `THIRD_PARTY.md`.

## Download
A prebuilt APK is on the [releases page](https://github.com/laghab/wisp/releases/latest) (`arm64-v8a` for almost every recent phone). It is signed with the Android debug key; see the release notes. The store listing text and screenshots are in `fastlane/metadata/android/`.

## Build and install
Needs the Rust toolchain with the Android targets and `cargo-ndk` (`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android && cargo install cargo-ndk`). Gradle builds `core/` automatically.
```bash
(cd core && cargo test)                      # Rust core tests, run on the host
./gradlew assembleDebug                      # app/build/outputs/apk/debug/ (one APK per CPU, and a universal one)
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./gradlew testDebugUnitTest :net:test        # unit tests
tools/e2e/all.sh <adb-serial>                # on-device checks (Android emulator or Waydroid)
tools/bench/budgets.sh <adb-serial>          # performance budgets (docs/perf.md)
tools/check-network.sh                       # network and permission rules
```
Use `app-x86_64-debug.apk` on an x86 emulator or Waydroid. A release build is `./gradlew assembleRelease` (see `docs/reproducible-builds.md`).

## User guide

### First run
Open **Wisp** from the app list. Three steps: turn the keyboard on in Android's list, switch to it, and read the one-screen privacy summary. After that the app is your settings.

### Typing and swiping
- Tap keys as usual. Hold a key for its extra characters (hold **e** for é and **3**): slide to one and let go.
- Swipe across the letters to type a word. One backspace removes the whole swiped word. If a swipe came out wrong and you delete it and type the word by hand, Wisp learns from that.
- Slide a finger across the space bar to move the cursor. Hold backspace to delete faster, then whole words.
- The globe key switches to your previous keyboard; hold it for the system's keyboard list. It is on every layout and can not be removed.
- The strip above the keys shows suggestions while you type and **Select all / Cut / Copy / Paste / Private** otherwise. **Private** pauses learning.
- The language key (EN / HI) switches between English and Hinglish.

### Voice typing
Tap the microphone at the right end of the strip. Tap once to talk hands-free and tap again to finish, or hold it to talk while it is held (slide away to cancel). **Undo dictation** removes exactly what was inserted. The first time, Wisp asks for the microphone permission, and if no speech model is installed it takes you to **Voice typing** in settings to pick one.

### Rewrite (writing assistant)
Tap the sparkle on the strip. Wisp rewrites the selected text, or the sentence before the cursor, with a small AI model that runs on your phone: **Polish, Friendly, Formal, Shorter, Bullets** or **Translate to Hindi**. The result appears in a card; nothing in the field changes until you tap **Replace draft** or **Insert below** (**Regenerate** tries again). No model is installed by default: choose Qwen 2.5 0.5B, Gemma 3 1B or Qwen 2.5 1.5B under *Voice typing, Models*, or switch the writing assistant off. The model is loaded only for a rewrite and freed with the speech model after the idle timeout. The engine is llama.cpp, built by `tools/build-llm.sh` for arm64 and x86_64 and pinned in `docs/native-libs.sha256`.

### Choosing a speech model (Settings, Voice typing)
Each model lists what it is good at and what it costs. Wisp recommends the most accurate one that leaves your phone enough memory, and **Test speed** measures any installed model on your own phone. Download over Wi-Fi, or download the archive in a browser and use **From file**.
- *Moonshine Tiny / Base*: small and fast, English only.
- *Parakeet 110M*: very accurate English.
- *Parakeet V3*: most accurate, 25 European languages, needs a lot of memory.
- *Whisper Base / Small*: 99 languages including Hindi (written in Devanagari letters).
- *SenseVoice*: very fast, Chinese, Japanese, Korean and English.

### Learning, and taking it back
- **Practice swiping** shows 20 words; swipe each one. A few minutes a day tunes the decoder to your hand.
- **What Wisp learned** lists your learned words: remove one, export all, or erase everything.
- **Apps to never learn from** lists apps where nothing is remembered. Password fields and Private mode are never learned from.
- **Import a word list** adds words (a text file with one word per line, a tab, and a number from 0 to 255 for how common it is) to a language.

### Backup and restore (Settings, Backup and restore)
Pick a folder once. **Back up now** writes one dated `.zip`. Choose daily or weekly and Wisp makes one when you open the keyboard or the app after the interval (no background service). Scheduled backups keep the newest N (you choose); the ones you make by hand are never deleted. To restore, tap a backup in the list or **Restore from a file**; Wisp checks the file, asks you to confirm, and then replaces your learned words, layouts and settings. **Move settings to another phone** exports settings and layouts, and the learned data only if you tick that.

### Layouts
**Layouts and toolbar** edits the letter, symbol and more-symbol layouts as text. A mistake is reported with its line; the old layout stays. See `docs/layouts.md` for the format.

## Privacy statement
**What Wisp stores, on your phone only:** your learned words and word pairs, your swipe style and practice results, imported word lists, your settings, layouts, backups you ask for, and the speech models you install. Nothing else: no typed text history, no recordings, no transcripts.

**What leaves your phone:** nothing, unless you use the Downloads tab. It is off until you switch it on, it only runs when you press a button there (download a model, check for updates, download a Wisp update), and it only contacts the servers it lists. Typing, swiping, learning and dictation never use the network. The code that can use the network is one module (`net/`), and `tools/check-network.sh` fails the build if network code appears anywhere else.

**Permissions:** `RECORD_AUDIO` (asked for the first time you use voice), `INTERNET` (Downloads tab only), `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE` (to show a download's progress), `REQUEST_INSTALL_PACKAGES` (to hand a downloaded update to Android's installer, which asks you), `VIBRATE` (key feedback). No contacts, location, storage or accounts.

**Audio:** recorded in memory while the microphone indicator is showing, discarded as soon as the text is produced, never written to disk, and the microphone is disabled in password fields.

**Erasing it all:** *What Wisp learned, Erase everything learned*, or clear the app's storage in Android's settings.

## Layout of the code
`app/src/main/java/app/wisp/keyboard`: `kbd/` keys, layouts, input logic · `dict/` word suggestions (Rust lexicon) · `gesture/` swipe typing (Rust decoder) · `personal/` learning · `voice/` dictation (own process) · `downloads/` and `net/` (the only network code) · `backup/` · `style/` brand and themes · `ui/` settings screens (own process).
