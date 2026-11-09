# Third-party code and data

Wisp is licensed GPL-3.0 (see `LICENSE`). Code and data reused from other projects:

| Source | License | What | Status |
|---|---|---|---|
| Handy for Android (own project) | MIT | voice engine, audio, model catalogue, text post-processing | in use |
| Handy (cjpais/Handy) | MIT | post-processing logic, ported via Handy for Android | in use |
| sherpa-onnx (k2-fsa) v2.0.1 | Apache-2.0 | speech recognition runtime and bindings (Kotlin API vendored under `com/k2fsa`, prebuilt `.so` files pinned in `docs/native-libs.sha256`) | in use |
| ONNX Runtime | MIT | inference runtime | in use |
| HeliBoard / AOSP LatinIME | GPL-3.0 / Apache-2.0 | native dictionary engine (`app/src/main/cpp/latinime`, built as `libjni_latinime`) and its Java bridge classes (`app/src/main/java/com/android/inputmethod`) | in use |
| `main_en_US.dict` from HeliBoard / aosp-dictionaries ("legacy" US English main dictionary, 160,715 words, 2014-10-31) | not stated: upstream lists a `.source` file for this row with no licence text (the "experimental" rows say CC BY 4.0, this one does not) | English dictionary (`app/src/main/assets/dicts/main_en_US.dict`) | in use for personal builds; resolve the licence before distributing the app |
| Silero VAD | MIT | voice activity detection model | in use |

Keep each reused file's original copyright header. Dictionary data licences must be checked before bundling (task 3.2).
