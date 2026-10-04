# SimpleLink

SimpleLink is a minimal Android-to-Android remote support app.

**One code. One approval. Full remote help.**

The person sharing their phone opens SimpleLink, gets a temporary 6-digit code, and approves the incoming request. The helper can then view and control the phone without accounts, device dashboards, permanent IDs, or connection-mode setup.

## Features

- Temporary 6-digit connection codes
- Explicit **Allow / Deny** approval on the phone being shared
- Live Android screen streaming with WebRTC
- Remote taps, long-presses, swipes, typing, and Android navigation
- Internet connections with automatic network recovery
- Nearby Wi-Fi Direct fallback when both phones are close
- Adaptive stream quality for weak or changing networks
- Controller session stays alive when SimpleLink is removed from Recents
- Rescue SMS flow for helping a remote phone recover connectivity
- No account system and no permanent unattended-access password

## Simple flow

### Share a phone

1. Open SimpleLink.
2. Tap **Share This Phone**.
3. Send the 6-digit code to the helper.
4. Review the request and tap **Allow**.
5. Accept Android's required screen-capture permission.

### Access another phone

1. Open SimpleLink.
2. Tap **Access Another Phone**.
3. Enter the 6-digit code.
4. Wait for the other phone to approve the request.
5. Control the remote phone from the full-screen viewer.

## Security model

SimpleLink does not bypass Android security controls.

- Every remote-support request requires target-side approval.
- Screen sharing uses Android MediaProjection.
- Remote control uses an AccessibilityService that the target user explicitly enables.
- SimpleLink disables its Accessibility control when it is no longer required.
- WebRTC media/control traffic is protected with DTLS/SRTP.
- Internet signaling is rate-limited and protected by the server-side Abuse Shield.
- Release builds reject cleartext networking.
- Temporary codes and session identities are not permanent remote-access credentials.
- Rescue SMS can request/recover a session, but it never authorizes remote control by itself.

Android can still block capture or control on secure system surfaces, DRM content, banking/password screens, or OEM-protected UI.

See [SECURITY.md](SECURITY.md) for the detailed security design.

## Connectivity

SimpleLink chooses the available path automatically.

| Situation | Behavior |
| --- | --- |
| Both phones have internet | WebRTC internet session |
| Phones are nearby without internet | Wi-Fi Direct/local session |
| Network changes during a session | Automatic reconnect/recovery |
| Remote target lost internet but can receive SMS | Rescue SMS can guide the owner back online and resume the normal connection |
| No internet, no nearby path, and no cellular/SMS path | Remote communication is not possible |

## Requirements

- Android 8.0+ (`minSdk 26`)
- Android Studio with Android SDK 37
- JDK 17+
- Two Android devices for realistic remote-control testing
- Node.js 22+ for the Cloudflare signaling Worker

## Build

Copy the local configuration template:

```bash
cp local.properties.example local.properties
```

Set your Android SDK path and signaling endpoint in `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
simplelink.signalingUrl=wss://YOUR_SIGNALING_HOST/ws
```

Run tests and build the debug APK:

```bash
./gradlew test assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

Install on a connected test device:

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## Signaling

### Cloudflare Worker

The `cloudflare/` project contains the production-style signaling Worker and Abuse Shield.

```bash
cd cloudflare
npm ci
npm run check
```

Deploy it only after configuring your own Cloudflare environment:

```bash
npm run deploy
```

Live-safe tests require an explicit endpoint:

```bash
SIMPLELINK_TEST_WS=wss://YOUR_SIGNALING_HOST/ws node test/live-safe-smoke.mjs
```

### Local signaling server

A Node.js WebSocket signaling server is also included for local/self-hosted development:

```bash
cd server
npm ci
npm test
PORT=8787 npm start
```

For local debug builds, point `simplelink.signalingUrl` at the development server.

## Project structure

```text
SimpleLink/
├── app/          Android application
├── cloudflare/   Cloudflare signaling Worker + abuse protection
├── server/       Optional Node.js signaling/TURN deployment
├── scripts/      Reliability/startup test helpers
├── gradle/       Gradle wrapper
├── README.md
└── SECURITY.md
```

## Validation

The project includes automated checks for:

- Android unit tests
- x86 and ARM64 Android builds
- signaling request/approval flows
- reconnect and network-handoff behavior
- Abuse Shield limits
- adaptive WebRTC quality and telemetry
- repeated startup and signaling soak tests

Real release validation should still be performed on at least two physical Android phones across different networks.

## Privacy

SimpleLink is built for temporary, owner-approved support sessions. It is not designed for hidden monitoring, unattended spying, or bypassing Android permission prompts.
