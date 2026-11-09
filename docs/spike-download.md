# Spike 1.2: model download without INTERNET

**Question:** can the keyboard app fetch a model through the system `DownloadManager` while declaring no `INTERNET` permission? (design.md D9)

## Setup
- Debug-only `SpikeDownloadActivity` (`app/src/debug`) enqueues a `DownloadManager.Request` to `getExternalFilesDir`.
- Manifest verified to hold no `INTERNET` (`dumpsys package` shows an empty requested-permissions list).
- Device: emulator, Android 14 (API 34), network reachable (ping succeeded).

## Result
`dm.enqueue()` throws:

```
java.lang.SecurityException: Permission Denial: writing com.android.providers.downloads.DownloadProvider
uri content://downloads/my_downloads from pid=..., uid=... requires android.permission.INTERNET, or grantUriPermission()
```

**The system download service does not work without `INTERNET`** (at least on API 34; only one system image was available, so Android 12 and the newest release were not tested; this check is enforced in the download provider, so other versions are unlikely to differ).

## Consequence for design D9
Fallback order from the design now applies:
1. A separate small companion app that holds `INTERNET` and only fetches model files.
2. Import-from-file only (user downloads the archive in a browser, then picks it in Wisp).
3. (Rejected by the spec) adding `INTERNET` to the keyboard app itself.

## Decision
The user chose option 3 with extra controls: Wisp declares `INTERNET`, but all network code sits in one `:net` module used only by a Downloads tab, a network kill switch is off by default, and the tab tracks updates for models and for Wisp itself. Design D9, the privacy and model-management specs, and tasks 7.3, 7.6 and 10.1 were updated to match.
