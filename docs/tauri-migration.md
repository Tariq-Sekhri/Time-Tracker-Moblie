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

## Validation completed (2026-10-15)

- Shared/frontend suite: 104 passed, 1 existing skipped test. The final Notes
  layout change also passed its two focused tests and all seven mobile tests.
- Desktop React production build passed after adding the mobile entry.
- Android: 13 unit tests passed; Android lint, Cargo formatting and Android-target
  Clippy (`-D warnings`) passed.
- Bundled x86_64 debug APK installed on a fresh Android 15 / API 35 emulator.
  The full integration test passed against its own loopback MockWebServer:
  existing-log queries and totals, every navigation page, native preferences,
  shared rich Notes saves/reloads, timer start/stop, device listing, sync uploads,
  acknowledged deletion tombstones and safe server disconnection.
- Enabled Usage Access through Android's real settings screen. The tracking
  service remained a foreground service while Settings was in front; its SQLite
  log continued increasing without React running in the foreground.
- Browser accessibility and media notification-listener services both bound
  successfully under the isolated debug package.
- Reopened the app in airplane mode after stopping its process. Saved notes,
  preferences and activity remained available from native storage.
- Reviewed rendered Usage, Statistics, Notes, software-keyboard layout and the
  link dialog. Android Back closes the dialog and follows page history.
- Signed arm64 release AAB built successfully. Its certificate matches the
  existing workspace `app-release.aab`. ELF load segments use 16 KB alignment.
- Packaged manifests verify the original release ID, isolated `.dev` debug ID,
  and all three tracking services. Release version remains 0.5.2 / code 9;
  perform the normal version bump before publishing a new Play release.
- The phone review APK also passes `apksigner verify` and 16 KB `zipalign` checks.
  Final AAB SHA256:
  `11a19ee50e0040b55d174456d5f33171d340c2a092a2fb371fe056a3631710d6`.

Debug package isolation is configured with Tauri's `debugApplicationIdSuffix`.
The CLI synchronizes that setting into Gradle and removes a hand-written Gradle
suffix when the Tauri setting is absent. Build helpers now verify the actual
merged manifest, rather than relying on source configuration alone.

SQLite range queries explicitly cast bound end-time comparisons to integers:
`COALESCE(...)` has no column affinity and otherwise compares integer values to
text parameters incorrectly. The seeded integration test caught this regression.

## Review artifacts and limits

- `artifacts/time-tracker-mobile-arm64-debug.apk`: isolated phone review build.
- `artifacts/time-tracker-mobile-x86_64-debug.apk`: emulator review build.
- `artifacts/time-tracker-mobile-arm64-release.aab`: signed release candidate.
- `.qa/instrumentation-final.txt`: passing integration-test output.
- `.qa/final-notes.png`, `.qa/final-link.png`, `.qa/tracking.png`: screenshots.
- `.qa/final-checks.log`: formatting, Clippy, Android unit tests and lint.

These are local, ignored build/QA outputs. No real app, production database or
backend was changed. No release was published and neither branch was pushed.
The workspace helper scripts delegate to tracked implementations in `scripts/`.
The two workspace wrapper files are outside either Git repository; their
implementations and matching usage documentation are committed here.

Physical-phone battery/background behavior, real browser/media playback capture,
and an installed-production upgrade have not been exercised. Existing Kotlin
tracking engines and schema are retained. The Android 17 / 16 KB emulator image
did not boot in this environment; API 35 was used for functional QA, with 16 KB
alignment checked separately in the native binaries. CI is prepared but has not
run; push the pinned shared React revision before running it remotely.
