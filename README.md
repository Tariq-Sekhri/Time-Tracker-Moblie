# Time Tracker Mobile

Android 13+ app that records on-device app usage and turns it into a dashboard, app breakdowns, live logs, calendar day blocks, and statistics.

- Usage Access is required for the app to run.
- Browser and media tracking are optional.
- Notes are local and can be hidden without deleting them.
- Sync is optional: connect to a private Time Tracker server, register the device, and wait for approval. Mobile uploads its own logs; other-device subscriptions are disabled.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

Local data stays on the device unless Sync is configured. See [privacy.md](privacy.md).
