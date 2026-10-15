# Tauri mobile migration

The Android application keeps its Kotlin tracking engine, SQLite database
(`time_tracker.db`), SharedPreferences, service component names and release
application ID (`ca.tariq_sekhri.time_tracker`). Tauri hosts bundled React assets.
The desktop repository next to this checkout owns the shared React source.

Work is on `migration/tauri-mobile` in both repositories. Nothing is pushed.
Debug builds use a separate `.dev` application ID and cannot access installed
production app storage. Never install a release over the real app during QA.

## Baseline (2026-10-15)

- Desktop: `npm run build` passed; `npm test`: 97 passed, 1 skipped.
- Android: `./gradlew :app:testDebugUnitTest :app:lintDebug` passed.
- No device/emulator testing before the implementation and static checks finish.

## Architecture

- `../time tracker/src/mobile`: mobile React entry and native API adapter.
- Existing desktop React components are imported directly, without copying.
- `src-tauri`: small Android Tauri host, separate from desktop lifecycle/tray code.
- `app/src/main`: existing Kotlin storage, tracking, sync, and native bridge.
- `src-tauri/gen/android`: Android packaging/build entry for Tauri.

Tracking and WorkManager run in Android services/workers, independent of React.
The UI never receives device tokens and does not download other-device logs.

## Implementation checkpoints

1. Scaffold the Tauri Android host and establish checks.
2. Connect native lifecycle, storage, permissions, and sync.
3. Implement shared React mobile screens and native API contracts.
4. Verify builds, lint, unit tests and packaging; then perform isolated device QA.
