# Time Tracker Mobile

**Alpha.** A working Android app for reviewing app usage, activity logs, and statistics. The UI is still being refined. Android 13+ and Usage Access permission are required.

Usage data stays on device by default. Optional sync connects to a private [Time Tracker Backend](../Time-Tracker-Backend/README.md); devices require admin approval.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK: `app\build\outputs\apk\debug\app-debug.apk`

See [privacy.md](privacy.md) for data handling details.
