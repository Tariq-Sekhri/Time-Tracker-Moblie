# Time Tracker Mobile

Android app for tracking device app usage locally and optionally syncing it to a private Time Tracker Backend.

## Requirements

- Android 13+ (API 33)
- Usage Access permission
- Optional: a reachable backend on port `8765`

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

## Sync

1. Open **Sync** in the app and enter the backend machine's IP address (not `localhost`).
2. Check the connection and register the device.
3. In the backend admin panel, approve the new device.
4. Return to the app and sync. Background sync runs periodically when configured.

Usage data stays on the device unless you configure sync. See [privacy.md](privacy.md) for details.
