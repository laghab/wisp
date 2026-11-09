# Spike 1.3: speech engine in a separate `:voice` process

**Questions:** (a) does sherpa-onnx run in its own process and exchange audio/text with the keyboard process? (b) does exiting that process return the memory? (c) can the keyboard process capture the mic while its view is shown? (design.md D2, D3)

## Setup
Debug-only code in `app/src/debug`: `SpikeVoiceService` (`android:process=":voice"`, Messenger IPC, idle timer that calls `Process.killProcess`), `SpikeVoiceActivity` (driver), `SpikeIme` + `SpikeFieldActivity` (mic from an IME). Model: Moonshine Tiny (int8). Device: x86_64 emulator, Android 14 (API 34). Numbers from an emulator are indicative only; repeat on the phone (task 1.4/6.7).

## Results
| Check | Result |
|---|---|
| Engine in separate process, PCM in / text out over Messenger | Works. Test clip (5.8 s) transcribed correctly: "Ask not what your country can do for you. Ask what you can do for your country." |
| Model load (Moonshine Tiny) | 616-646 ms |
| Decode of 5.8 s clip | 268-278 ms |
| Memory, keyboard-side process (spike activity) | ~32 MB PSS |
| Memory, `:voice` with tiny model loaded | ~128 MB PSS (195 MB RSS) |
| After idle timeout | process exits; `ps` shows only the main process |
| Mic from the keyboard process while its view is shown | `AudioRecord` initialized, recording state 3, not silenced, ~2 s of samples read |

## Finding that changes the design
After the `:voice` process exited, Android **restarted it about 1.5 s later** because the client was still bound with `BIND_AUTO_CREATE`; the restarted process then reloaded the model on its own (the client re-sent LOAD from `onServiceConnected`). That would silently defeat the idle unload.

**Rule going forward:** the voice service announces its exit to the client; the client unbinds on that message (and never loads in `onServiceConnected`); the next mic press binds again. The model is only ever loaded in response to a user action.

## Not covered
- A real phone's microphone, background-audio rules and low-memory behavior (rechecked in task 6.3).
- Binder throughput for long dictations; chunks are small (about 32 KB/s), so shared memory is not needed unless profiling says so.
