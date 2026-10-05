# LAN Remote

Self-contained, **non-root** Android app that lets a browser on the **same local
network** view and control the device — screen, touch, scrolling, navigation keys,
text input, and **device audio**. No PC in the loop at runtime, no cloud, no
signaling server. Open a URL (or scan a QR code) and you're connected.

## Features

- **Live screen** streamed to any browser (H.264 or MJPEG — chosen automatically).
- **Touch control**: tap, long-press, swipe/drag.
- **Scrolling**: mouse wheel / trackpad, plus click-drag.
- **Navigation keys**: Back, Home, Recents.
- **Text input** from the controller keyboard.
- **Device audio** (media/game playback) streamed as Opus.
- **Connect easily**: prominent URL, a QR code, and a **Share link** button that
  sends the URL through any app (WhatsApp, Telegram, …).
- **Live session stats**: viewer state, video mode + resolution, fps, bitrate,
  audio on/off, data sent.
- **Stream quality controls**: resolution (100% / 75% / 50% of the display),
  frame rate (15 / 30 / 60 fps) and MJPEG JPEG quality (Low / Medium / High).
  Set them on the phone or from the browser's quality bar; both stay in sync.
  Changes apply live without stopping the share, and the H.264 bitrate follows
  pixel count and frame rate.
- **Rotation aware**: turning the phone rebuilds the stream at the new
  orientation, and taps are always mapped to the real screen.

## How it works

```
Controller (browser)                    Target Android device
┌────────────────────┐                  ┌─────────────────────────────────────┐
│ canvas  <───────────┼── /video (H.264) │ CaptureService (owns MediaProjection)│
│  WebCodecs / <img>  │   or /mjpeg (JPEG)│  ├─ ScreenEncoder  → H.264 (MediaCodec)
│                     │                  │  └─ JpegCapturer   → MJPEG (ImageReader)
│ pointer/wheel/keys  ┼── /control (JSON) │ ControlService (AccessibilityService)│
│                     │                  │  dispatchGesture / globalActions / setText
│ WebAudio  <──────────┼── /audio (Opus)  │ AudioCapturer (AudioPlaybackCapture)  │
│  WASM Opus decode   │                  │  → Opus (Concentus, pure Java)       │
└────────────────────┘                  │ RemoteServer (Ktor CIO, LAN)         │
        ▲── HTTP (index.html, client.js,│ Stats (live counters)                │
        │       opus-decoder.min.js)     └─────────────────────────────────────┘
```

Video, audio, and control are independent pipelines. Video (one VirtualDisplay)
runs one transport at a time; audio (AudioRecord) runs alongside it. Each starts
on demand when its browser socket connects and stops on disconnect. One viewer at
a time.

## Video transport: H.264 vs MJPEG

The browser's `WebCodecs` H.264 decoder only exists in a **secure context** (HTTPS
or `http://localhost`). Over a plain `http://<LAN-IP>` page it is undefined, so the
client **auto-selects**:

| Context | Transport | Notes |
|---------|-----------|-------|
| `http://<phone-ip>:8080` (plain Wi-Fi/LAN) | **MJPEG** | Any browser, no TLS. Higher bandwidth; fine on LAN. |
| `http://localhost:8080` via `adb forward` | **H.264** | Secure context over USB; low latency/bandwidth. |
| `https://…` (self-signed, future) | **H.264** | Secure context over Wi-Fi. See *Hardening*. |

Both pipelines are validated. H.264 is the efficient path; MJPEG is the
universally-compatible default for plain-HTTP LAN.

## Audio

Device **playback** audio (music, video, games) is captured via
`AudioPlaybackCapture` (from the same screen-capture consent), encoded to **Opus**
(Concentus, pure-Java — Android's `MediaCodec` has no Opus *encoder*), streamed over
`/audio`, and decoded in the browser by a **WASM Opus decoder** into Web Audio
(with a ~120 ms jitter buffer). Click **Enable audio** in the web UI (browsers
block autoplay until a gesture).

- Requires the `RECORD_AUDIO` permission.
- **OS limits:** only media/game/unknown-usage audio from apps that permit capture.
  Phone/VoIP calls can't be captured; apps that set `allowAudioPlaybackCapture=false`
  are silent (audio analog of `FLAG_SECURE`).
- A/V sync is loose (audio trails video by the buffer depth).

## Build & install

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME="/path/to/jdk-17+"
./gradlew :app:installDebug
```

## One-time device setup

1. **Accessibility** — enable "LAN Remote" so it can inject input:
   Settings → Accessibility → LAN Remote → On. Without it you'll see the screen but
   taps/typing won't work; the app shows a warning with a shortcut to this screen.
   > Reinstalling/updating the app disables this service (an Android security
   > measure) — re-enable it if control stops working after an update.
2. **Notifications** — allow the ongoing "screen is being shared" notification.
3. **Microphone** (`RECORD_AUDIO`) — only needed for audio; screen/control work
   without it.

## Usage

1. Open **LAN Remote**, tap **Start sharing**, accept the capture prompt (choose
   **Entire screen**).
2. The app shows a URL like `http://192.168.1.42:8080`, a QR code, and a **Share
   link** button.
3. On a device on the same network, open that URL (or scan the QR).
4. Click/drag to control, wheel to scroll, use Back/Home/Recents, type to send
   text, click **Enable audio** for sound.
5. Tap **Stop sharing** when done.

## Known limits (OS-enforced, not bugs)

- **Secure surfaces are black.** `FLAG_SECURE` screens (banking, password/PIN,
  DRM video) render black under `MediaProjection` and can't be gesture-controlled.
- **Capture consent per session.** Android shows the capture prompt each time.
- **Text input** uses Accessibility `ACTION_SET_TEXT`; some custom views resist it.

## Security posture

**v1 is unauthenticated: any device on the LAN that reaches the port during an
active session can connect.** Mitigations that add no login friction: sharing is
off by default and started per session; the server binds only while sharing; a
persistent notification shows while live.

> Use on trusted home/personal networks only. On shared/public Wi-Fi, anyone on
> the network can control the phone during a session.

**VPN / remote note:** a controller reaching the LAN over a VPN connects exactly
like a same-Wi-Fi client (still plain HTTP → MJPEG). This *amplifies* the no-auth
risk — the trusted set becomes anything that can reach the VPN — so PIN/QR + TLS
matters more the moment remote/VPN access (or a shared link) is used.

### Hardening (future / v2)

- **PIN/QR pairing + self-signed TLS.** TLS also unlocks H.264+WebCodecs over
  Wi-Fi (secure context). Needs an HTTPS-capable Ktor engine (e.g. Netty) + keystore.
- Clipboard sync, file transfer.
- Native Android controller client (decodes the same H.264 via `MediaCodec`).
- Internet reach: signaling server + TURN relay.

## Updating

On launch the app asks GitHub for the latest release. If it is newer than the
installed version, a banner offers **Update**: the APK is downloaded, its SHA-256
is checked against the release asset digest, and the system installer opens.
Android always asks for confirmation; there is no silent install for sideloaded
apps. The first time, Android will also ask you to allow LAN Remote to install
unknown apps.

> Updating disables the accessibility service (an Android security measure);
> re-enable it afterwards. The app reminds you.

## Releasing

Updates only install over an existing app if both are signed with the **same
key**, so releases must use a dedicated keystore, not the per-machine debug key.

1. **Create the keystore once** and keep it safe (losing it means users must
   uninstall to take the next update):

   ```bash
   keytool -genkeypair -v -keystore ~/keys/lanremote-release.jks -alias lanremote \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Create `keystore.properties`** in the repo root (git-ignored):

   ```
   storeFile=/Users/you/keys/lanremote-release.jks
   storePassword=…
   keyAlias=lanremote
   keyPassword=…
   ```

   Without this file `assembleRelease` falls back to the debug key and prints a
   warning. Never publish such a build.

3. **Bump the version** in `app/build.gradle.kts`: `versionCode` +1,
   `versionName` to `X.Y` or `X.Y.Z`. The update check compares the release tag
   against `versionName`, so they must match exactly.

4. **Build, tag and publish:**

   ```bash
   ./gradlew :app:assembleRelease
   cp app/build/outputs/apk/release/app-release.apk lan-remote-<version>.apk
   git tag v<version> && git push origin v<version>
   gh release create v<version> lan-remote-<version>.apk --title "LAN Remote v<version>" --notes "..."
   ```

   Only the first `.apk` asset of the latest non-prerelease release is offered to
   users. Mark test builds as pre-release so they are skipped.

> **v0.1 note:** v0.1 was signed with a debug key. The first keystore-signed
> release cannot install over it; say so in that release's notes and ask users to
> uninstall once.

## Project layout

- `MainActivity` — UI, session start/stop, QR, live stats, share link, control-state warning.
- `Stats`, `QrGenerator` — live counters and QR bitmap generation.
- `control/` — `ControlEvent` model + parser, `CoordinateMapper`, `ControlService`.
- `video/` — `ScreenEncoder` (H.264), `JpegCapturer` (MJPEG), `AudioCapturer` (Opus),
  `CaptureService`, `CapturePipeline`, `VideoFrame`.
- `server/` — `RemoteServer` (Ktor), `NetworkUtil`.
- `update/` — `UpdateChecker` (GitHub Releases + version compare), `ApkDownloader`
  (streaming download + SHA-256), `ApkInstaller`/`InstallResultReceiver`
  (PackageInstaller session), `UpdateManager` (flow + banner state).
- `assets/web/` — `index.html`, `client.js` (auto-selecting web client),
  `opus-decoder.min.js` (vendored WASM Opus decoder).
- `docs/superpowers/` — design spec and implementation plan.

## Tech stack

Kotlin, `minSdk 29`, `compileSdk 35`; embedded **Ktor CIO** server; **Concentus**
(Opus encode), **ZXing** (QR), **opus-decoder** WASM (browser Opus decode);
vanilla-TS/JS web client.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Covers control-event parsing/validation, coordinate mapping, NAL framing, GitHub
release parsing and version comparison, and APK download/verification.
