# Auto-update — design

**Date:** 2026-10-05
**Status:** approved in conversation, pending written review

## Goal

Let a sideloaded LAN Remote install notice that a newer release exists on
GitHub and install it with one tap. Android does not allow a non-system app to
install silently, so "auto" means: automatic *check* on launch, one-tap
*download + install*, with the OS confirmation dialog in between.

## Decisions already made

| Question | Decision |
|---|---|
| How proactive | Check once per app launch; no background work, no notifications. |
| Update source | GitHub Releases API (`/repos/maparham/remote_android/releases/latest`). |
| Signing | Dedicated release keystore read from an untracked `keystore.properties`; debug key fallback with a loud warning when the file is absent. |
| New dependencies | None. `HttpsURLConnection`, coroutines and kotlinx-serialization are already available. |

## User-facing behaviour

1. On `MainActivity.onCreate`, a coroutine fetches the latest release in the
   background. The UI is never blocked.
2. If the release is newer than the installed version, a banner appears above
   the existing content:

   > **LAN Remote vX.Y is available.** You will need to re-enable the
   > accessibility service after updating.
   > [Update] [Later]

3. **Later** hides the banner until the next launch (process restart). No
   persistent "skip this version" state.
4. **Update**:
   - If `packageManager.canRequestPackageInstalls()` is false, open
     `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package. When the
     activity resumes with permission granted, continue automatically.
   - Download the APK to `cacheDir/updates/<asset-name>` with a progress
     indicator in the banner (button disabled, percentage text).
   - Verify the SHA-256 against the asset `digest` from the API.
   - Hand the file to a `PackageInstaller` session and commit it. Android
     shows its confirmation dialog, then installs and relaunches nothing; the
     user reopens the app.
5. Installer cancellation: nothing happens; the banner stays with the Update
   button re-enabled.

## Components

All new code lives in `com.example.lanremote.update`.

### `ReleaseInfo` (data class)

`versionName: String` (tag with leading `v` stripped), `apkUrl: String`,
`apkName: String`, `sizeBytes: Long`, `sha256: String?` (hex, lower-case,
`null` if the API gives no digest).

### `UpdateChecker`

- `suspend fun fetchLatest(): ReleaseInfo?` — GET the releases/latest
  endpoint with `Accept: application/vnd.github+json`, 10 s timeouts. Returns
  `null` on any network/HTTP error, if `prerelease` is true, if no asset ends
  in `.apk`, or if the tag does not parse.
- `fun parseRelease(json: String): ReleaseInfo?` — pure, unit tested. Picks
  the first `.apk` asset. Parses `digest` of the form `sha256:<hex>`.
- `fun compareVersions(a: String, b: String): Int` — pure, unit tested.
  Splits on `.`, compares numerically segment by segment, missing segments
  count as 0. Non-numeric segments make the function return 0 (treat as
  equal, so nothing is offered).
- `fun isNewer(latest: ReleaseInfo, installed: String): Boolean`.

### `ApkDownloader`

- `suspend fun download(info: ReleaseInfo, onProgress: (Int) -> Unit): File`
  — streams to `cacheDir/updates/`, deleting any stale files first. Computes
  SHA-256 while streaming. Throws `DigestMismatchException` if `sha256` is
  present and differs. Follows redirects (GitHub asset URLs redirect to a CDN).

### `ApkInstaller`

- `fun install(context, apk: File)` — opens a `PackageInstaller` session
  (`MODE_FULL_INSTALL`), writes the file, commits with a `PendingIntent` to
  `InstallResultReceiver` (mutable, since the system fills in extras).
- `InstallResultReceiver` (manifest-registered, `exported=false`): on
  `STATUS_PENDING_USER_ACTION` starts the confirmation `Intent` from the
  extras with `FLAG_ACTIVITY_NEW_TASK`; on `STATUS_SUCCESS` deletes the cached
  APK; on any failure status posts a `Toast` with the status message.

### `UpdateManager`

Orchestrates the three above for `MainActivity`. Exposes a simple state
(`Idle`, `Available(info)`, `Downloading(percent)`, `Installing`,
`Failed(message)`) through a callback on the main thread. Owns the
"dismissed this launch" flag.

### `MainActivity` changes

- Inflate a banner view (new `layout/update_banner.xml` included at the top of
  the existing layout, `visibility=gone` by default).
- Create `UpdateManager` in `onCreate`, call `checkForUpdate()`.
- In `onResume`, if an install was waiting on the unknown-sources permission
  and it is now granted, continue.

### Manifest

- `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- `<receiver android:name=".update.InstallResultReceiver" android:exported="false" />`

## Error handling

| Situation | Behaviour |
|---|---|
| No network, HTTP error, rate limit, prerelease, bad tag | Silent; `Log.w` only; no banner. |
| Download I/O error | Banner shows "Download failed", Update button re-enabled. |
| Digest mismatch | Cached file deleted; banner shows "Downloaded file failed verification". |
| Installer failure (e.g. signature mismatch with the installed app) | Toast with the system message. |
| User denies unknown-sources permission | Banner stays; tapping Update asks again. |

## Release-side changes

### Signing (`app/build.gradle.kts`)

```
keystore.properties (untracked):
  storeFile=/absolute/path/lanremote-release.jks
  storePassword=…
  keyAlias=lanremote
  keyPassword=…
```

If the file exists, a `release` signing config is created from it and used by
the `release` build type. If it does not exist, the release build type keeps
the debug signing config and Gradle logs
`WARNING: keystore.properties not found; release APK is signed with the debug key and will NOT install over keystore-signed builds.`

`.gitignore` gains `keystore.properties`, `*.jks`, `*.keystore`.

### Versioning rule

`versionName` is `X.Y` or `X.Y.Z`; `versionCode` increases by one per release;
the git tag is `v<versionName>`. The checker compares the tag against
`BuildConfig.VERSION_NAME`, so these must match. `BuildConfig` generation is
enabled in Gradle (`buildFeatures { buildConfig = true }`).

### README

New "Releasing" section: create the keystore once (`keytool` command), fill
`keystore.properties`, bump versions, `./gradlew :app:assembleRelease`, tag,
`gh release create v<version> app/build/outputs/apk/release/*.apk`. Note that
the first keystore-signed release cannot update a debug-signed v0.1 install;
users must uninstall once. Mention this in that release's notes.

## Testing

- Unit tests (`app/src/test/kotlin/.../update/`): `compareVersions` (equal,
  longer/shorter, non-numeric), `parseRelease` (normal, no apk asset, missing
  digest, prerelease).
- Manual end-to-end: install a build with `versionName=0.1`, publish a test
  release `v0.1.1` (can be a draft deleted afterwards; drafts are not returned
  by `releases/latest`, so use a real release), launch, confirm banner,
  Update, confirm dialog, verify new version runs.

## Out of scope

Background checks and notifications, "skip this version" persistence,
delta/patch updates, Play Store distribution, CI release workflow.
