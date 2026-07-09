# LAN Remote Access for Android — Design Spec

**Date:** 2026-07-09
**Status:** Approved for planning

## Summary

A self-contained Android app that lets a trusted controller on the **same local
network** view the device's screen and control it (touch, navigation keys, text
input). No root, no PC in the loop, no cloud. The controller connects from a web
browser in v1; a dedicated native Android client follows later.

## Goals

- Personal remote access to the user's *own* second Android device.
- No root; no ADB/host dependency; installable as an ordinary app.
- LAN-only reach (both devices on the same Wi-Fi).
- Controller uses a **web interface served by the phone** in v1. Native Android
  client is a later addition that reuses the same protocol.

## Non-Goals (v1)

- Access over the internet / NAT traversal (no signaling server, no TURN).
- Clipboard sync, file transfer, and audio streaming — deferred to v2.
- Authentication / pairing — explicitly out of scope per user decision (see
  Security).

## Use Case & Constraints

- **User:** the device owner, accessing their own second device.
- **Setup friction accepted:** install app, grant Accessibility + screen-capture
  consent once. No root, no ADB.
- **Network:** LAN only.

## Feature Scope (v1)

- Live screen view.
- Touch control: tap, long-press, swipe/drag.
- Navigation keys: Back, Home, Recents.
- Text input from the controller keyboard into the focused field.

Deferred to v2: clipboard sync, file transfer, audio, native Android client.

## Architecture

A single app on the **target** device runs three cooperating components plus an
embedded web server. A browser on the **controller** connects over the LAN.

```
Controller (browser)                    Target Android device
┌────────────────────┐                  ┌─────────────────────────────────────┐
│ canvas <- WebCodecs │◄── video WS ─────┤ CaptureService                       │
│  (H.264 decode)     │   (binary NALs)  │  MediaProjection→Surface→MediaCodec  │
│                     │                  │                                      │
│ pointer/key/buttons ├── control WS ───►│ ControlService (AccessibilityService)│
│  (JSON events)      │   (JSON)         │  dispatchGesture / globalActions /   │
└────────────────────┘                  │  setText                             │
        ▲                               │                                      │
        └────── HTTP (static assets) ───┤ WebServer (Ktor)                     │
                                        │ SessionManager (start/stop, notif)   │
                                        └─────────────────────────────────────┘
```

## Components (target app)

### SessionManager
The on/off switch and lifecycle owner.
- Sharing is **off by default**.
- User taps "Start sharing" → triggers `MediaProjection` consent prompt → starts
  the foreground service → binds the web server to the **LAN interface only** →
  posts a persistent "Screen is being shared" notification.
- Exactly one active session at a time.
- Displays connection info (device IP, port, URL, QR code) on the phone screen.
- This is the no-auth safeguard: the device is never silently controllable.

### CaptureService (foreground service)
- `MediaProjection` → `Surface` → `MediaCodec` hardware H.264 encoder.
- Emits H.264 NAL units with periodic keyframes (IDR).
- Stream begins with device resolution metadata for coordinate mapping.

### ControlService (`AccessibilityService`)
- Touch: `dispatchGesture` for tap, long-press, swipe/drag.
- Navigation: `performGlobalAction` for Back / Home / Recents.
- Text: `ACTION_SET_TEXT` on the focused editable node. A bundled IME
  (`InputMethodService`) is the documented fallback if `ACTION_SET_TEXT` proves
  unreliable on target apps — implement in v1 only if needed, else v2.

### WebServer (embedded Ktor)
- Serves the static web client (HTML/JS/CSS).
- `/video` WebSocket: binary NAL units, phone → browser.
- `/control` WebSocket: JSON events, browser → phone.
- Bound to the LAN interface only while a session is active.

## Web Client

- Opens `/video`, feeds NAL units to a `WebCodecs` `VideoDecoder`, paints to a
  `<canvas>`.
- Captures pointer and keyboard events on the canvas; maps pointer positions to
  **normalized [0,1] coordinates**; sends JSON over `/control`.
- On-screen Back / Home / Recents buttons.
- Requires a modern browser with WebCodecs (Chrome/Edge, Safari 16+).

## Data Flow & Coordinate Mapping

- **Video:** screen → MediaProjection → MediaCodec → H.264 → `/video` →
  WebCodecs → canvas.
- **Control:** pointer/key → `/control` JSON → ControlService → Accessibility.
- **Mapping:** the video stream declares device resolution; the browser always
  sends normalized [0,1] coordinates; the phone scales to real pixels. This makes
  control resolution-independent and robust to browser-window resizing.

### Control message schema (JSON, browser → phone)
- `{ "type": "tap", "x": 0.0-1.0, "y": 0.0-1.0 }`
- `{ "type": "longpress", "x": .., "y": .., "durationMs": .. }`
- `{ "type": "swipe", "x1": .., "y1": .., "x2": .., "y2": .., "durationMs": .. }`
- `{ "type": "key", "action": "back" | "home" | "recents" }`
- `{ "type": "text", "value": "..." }`

Coordinates are validated to [0,1]; malformed messages are dropped.

## Known Feasibility Limits (Honest Constraints)

- **Secure surfaces blank out:** screens with `FLAG_SECURE` (banking apps,
  password/PIN entry, DRM video) render black in `MediaProjection` and cannot be
  gesture-controlled. This is an OS-level guarantee; there is no non-root
  workaround.
- **MediaProjection consent prompt:** Android shows the capture-consent dialog
  each time a session starts. Unavoidable without root / Device Owner.
- **Text input edge cases:** `ACTION_SET_TEXT` works on standard editable views;
  some custom input views resist it. IME fallback mitigates this.

## Security Model

Per explicit user decision: **open on the LAN, no authentication** in v1. Anyone
on the same network who reaches the port during an active session can connect.

Mitigations that do not add login friction (all in scope):
- Sharing off by default; user must actively start each session.
- Server bound to the LAN interface only; not reachable off the local network.
- Persistent, non-dismissable notification while sharing is active.
- Self-signed TLS for the transport is **optional/deferred**; noted as the first
  hardening step if the user later wants it.

> Risk acknowledged: on an untrusted/shared Wi-Fi, any device on the network can
> take control during an active session. Recommended for trusted home/personal
> networks only. PIN/QR pairing + TLS is the natural v2 hardening.

## Tech Stack

- **Language:** Kotlin, coroutines.
- **minSdk:** 29 (Android 10) — stable `MediaProjection` and gesture dispatch.
- **Server:** embedded Ktor (HTTP + WebSocket).
- **Web client:** vanilla TypeScript, no framework.
- **Native controller client (deferred):** decodes the same H.264 stream via
  `MediaCodec`, reuses the `/control` protocol.

## Testing Strategy

- **Unit:** normalized→pixel coordinate mapping; `/control` message
  parsing/validation; NAL framing.
- **Instrumented:** AccessibilityService gesture dispatch and global actions.
- **Manual E2E:** connect from a laptop browser on the same Wi-Fi; verify
  latency, window-resize behavior, navigation keys, and text input.

## Implementation Findings (2026-07-10)

- **WebCodecs requires a secure context.** The browser `VideoDecoder` API is
  undefined over a plain `http://<LAN-IP>` page (not a secure context), so the
  approved H.264+WebCodecs transport cannot work over plain-HTTP Wi-Fi. This put
  two approved choices — H.264+WebCodecs *and* no-TLS plain HTTP — in direct
  conflict.
- **Resolution:** the H.264 pipeline is validated and retained (it works over
  `http://localhost` via `adb forward`, and over HTTPS). An **MJPEG** transport
  (`ImageReader` → JPEG → `/mjpeg`) was added for plain-HTTP LAN; it needs no
  secure context and works in any browser. The web client auto-selects H.264 in
  secure contexts and MJPEG otherwise. Capture pipelines start on demand per
  connecting client.
- Everything else was validated on a Pixel 8a (API 37): screen capture, H.264
  decode/render, tap/swipe control, and Back/Home/Recents.

## Open Items / Future (v2+)

- Native Android controller client.
- Clipboard sync, file transfer, audio.
- Authentication: PIN/QR pairing + self-signed TLS.
- Internet reach: signaling server + TURN relay.
