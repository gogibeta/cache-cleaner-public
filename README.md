# Cache Cleaner

An Android app that clears per-app cache **without root, Shizuku, or ADB** — using only the Android accessibility service to automate the same *App info → Storage → Clear cache → OK* screens you could tap by hand.

## Features

- **Junk-cache card** — total cache across your apps (from `StorageStatsManager`), updated on every scan
- **Per-app cache sizes** — sorted largest-first, with a **With cache** filter chip
- **Select individual apps or Select all** (With-cache-only filter included)
- **User whitelist** — tap the shield on any app to always skip it; manage it on the Whitelist screen
- **Automatic safety whitelist** — 56 system-critical packages (System UI, Settings, dialer, SMS, package installer, OEM managers…) plus your launcher, keyboard and this app itself are never offered for cleaning
- **Turbo mode** — no pause between apps (vs 1000 ms normally) and 50 ms click delays (vs 100 ms)
- **Live run log** — every step timestamped: opened App info, found Storage, tapped Clear cache, confirmed the dialog, cleaned / failed / skipped with reasons
- **Freed-bytes accounting** — cache-before/cache-after per app and a total in the result card
- **Progress notification** with cancel action and a summary when the run finishes

## How it works

For each selected app the app:

1. Opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package
2. Waits for the App info screen (Settings or MIUI Security Center)
3. Finds the **Storage** row (localized via the Settings app's own string resources, with English fallbacks) and taps it — scrolling if needed
4. On the Storage screen, taps **Clear cache** (localized, clickable-ancestor matching)
5. Confirms the optional confirmation dialog (localized "Clear"/"OK")
6. Returns to the app and moves to the next package

A clear-cache click is terminal for that app: one retry, then it is marked cleaned or failed. Cache size is measured with `StorageStatsManager.queryStatsForUid(...).cacheBytes` before and after; the difference is reported as freed bytes.

## Requirements

- Android 8.0 (API 26)+
- Accessibility service enabled for Cache Cleaner (the app guides you there)
- Usage-stats access (for per-app cache sizes)

## Honesty notes

- Cache-freed numbers are best-effort: `StorageStatsManager` can return stale values, and some OEM skins report odd numbers. The automation trace in the run log is the ground truth for what was actually tapped.
- OEM Settings screens (MIUI, EMUI, One UI…) vary; the engine resolves the Settings app's own localized labels at runtime, but physical-device testing on each skin is still the only real guarantee.

## Building

```sh
./gradlew :app:assembleDebug
```

Unit tests (pure JVM, no device needed):

```sh
./gradlew :app:testDebugUnitTest
```

GitHub Actions runs the unit tests, builds debug + release APKs, and runs an
end-to-end emulator test (install → enable accessibility → select → real
single-app cache-clean run, verified via the `[dbg]` logcat trace).
