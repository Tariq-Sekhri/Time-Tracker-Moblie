# Time Tracker Mobile

Android 13+ time tracking with a locally bundled React UI hosted by Tauri 2.
Kotlin owns usage/browser/media tracking, SQLite storage, background work and sync.

Usage data stays on device by default. Optional sync connects to a private [Time Tracker Backend](../Time-Tracker-Backend/README.md); devices require admin approval.

## Build

The matching React checkout must be next to this repository, named `time tracker`.
Use the `migration/tauri-mobile` branch in both repositories during migration.
`ui-source.json` records the matching React revision used by CI. Publish that
React commit before running the Android GitHub workflow; `REACT_UI_REF` can
override it for coordinated development builds.
Install Node, Rust, Android Studio, SDK 36, JDK 21 and NDK 27 or newer.

```powershell
npm ci
npm --prefix "../time tracker" ci
npm run check
npm run android:apk
npm run android:check
```

`android:apk` builds a bundled arm64 debug APK. `android:qa` builds an x86_64
emulator APK. Neither requires a running development server. Debug builds use
`ca.tariq_sekhri.time_tracker.dev`, with isolated storage and standard debug signing.
Outputs are under `src-tauri/gen/android/app/build/outputs/apk/`.
The build helper verifies the packaged application ID and tracking services.

`npm run android:dev` is optional live development and uses Vite on port 5174.

For release, put the existing signing properties in `signing/keystore.properties`
(or use the `RELEASE_STORE_*`/`RELEASE_KEY_*` environment variables), then run
`npm run android:release`. The AAB is under
`src-tauri/gen/android/app/build/outputs/bundle/universalRelease/`.
The release ID, database, preferences and signing key remain compatible with the
existing app. Test upgrades only on an isolated emulator before release.

Open `src-tauri/gen/android` in Android Studio. The old root Gradle project is
retained as migration history; it is no longer the build entry point.
The workspace `moblie_build.ps1` and `bump_moblie.ps1` helpers now delegate to
tracked scripts in this repository. `npm run bump -- patch` updates the Tauri,
Android, npm and Cargo versions together; update the changelog before releasing.

See [migration notes](docs/tauri-migration.md) for architecture and validation.

See [privacy.md](privacy.md) for data handling details.
