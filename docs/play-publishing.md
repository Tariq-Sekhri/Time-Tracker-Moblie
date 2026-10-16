# Google Play Internal testing automation

Push to this mobile repository's `main` branch to build the signed APK and AAB,
retain the existing GitHub APK release, and publish the AAB to Google Play
Internal testing. A manual workflow run on `main` does the same. `master` builds
remain build-only.

The shared React UI still comes from `ui-source.json` (or the existing
`REACT_UI_REF` repository variable). A push to the React desktop repository alone
does not publish this Android app; update the pinned mobile UI revision when
coordinating a mobile UI release.

CI assigns version code `100000 + GITHUB_RUN_NUMBER` and version name
`<mobile npm version>-ci.<run number>`. Local builds keep the configured versions.
Any future manual upload must use a higher code than the latest CI release.

The build saves `time-tracker-play-bundle` for 30 days. If the separate
`publish-play` job fails, use **Re-run failed jobs** to retry publishing the
saved signed bundle. Do not rerun a release already accepted by Play.

Signing uses the existing `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` GitHub Actions secrets.
`GCP_SERVICE_ACCOUNT` identifies `time-tracker-play-publisher` in project
`project-33477e16-5086-4c11-933`. `GCP_WORKLOAD_IDENTITY_PROVIDER` points to
`projects/837350808864/locations/global/workloadIdentityPools/android-apps-github/providers/github`.

Google verifies owner ID `34610622`, repository ID `1273985798`, and `main`.
Only this repository can impersonate this app's publisher; its Play permissions
cover viewing and testing releases for `ca.tariq_sekhri.time_tracker` only.
Publishing uses a short-lived OIDC token without a stored Google account key.
Production promotion remains a separate Play Console action.

CI builds from source and does not access production tracking data or sync.
