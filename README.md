# SimpleLink

SimpleLink is a minimal Android-to-Android remote support app.

The end-user flow is intentionally fixed and small:

1. Tap **Share This Phone** or **Access Another Phone**.
2. Share/enter the 6-digit code.
3. The phone being shared gets **Allow / Deny**.
4. After Android's required screen-capture consent, the helper sees the remote phone full-screen and can control it.

There are no accounts, device dashboards, connection-mode pickers, file-mode questions, or networking settings in the app UI.

## Connection behavior

| Situation | Behavior |
|---|---|
| Both phones online | Internet signaling + encrypted WebRTC session |
| Both phones offline and nearby | Wi-Fi Direct discovery + local WebRTC session |
| One phone offline and both nearby | Wi-Fi Direct/local session |
| Target phone is far away with internet switched off but SMS still works | Rescue SMS -> target Allow -> Android Internet panel -> automatic reconnect -> normal remote session |
| Target has no internet, no SMS/cellular path, no nearby path | Remote communication is physically unavailable |

## Rescue Mode

Rescue Mode does **not** request SMS inbox permissions.

When the helper chooses **Send Rescue SMS**, SimpleLink opens the normal SMS composer with a compact rescue message. The target app arms Google's SMS Retriever API while a share code is active. SMS Retriever can deliver the matching SimpleLink message without `READ_SMS` or `RECEIVE_SMS` permission.

The SMS also contains a `simplelink://rescue` link. If automatic retrieval is unavailable (for example, a device without Google Play services), the target user can tap that link in the SMS while offline.

A rescue SMS never authorizes remote control by itself. The target phone still shows **Allow / Deny**. If Accessibility control is not set up yet, Android's Accessibility settings are opened once. If internet is unavailable, Android's official Internet panel is opened. When internet becomes validated, SimpleLink registers the same temporary code and the helper automatically retries the same rescue request ID.

## Android security boundaries

SimpleLink does not bypass Android security prompts.

- Screen sharing uses MediaProjection and therefore requires Android's capture consent for the session.
- Remote taps, long-presses, swipes, text entry, and global navigation use an AccessibilityService that the target user enables explicitly.
- Android may intentionally block capture/control on secure system surfaces, DRM content, banking/password screens, or OEM-specific protected UI.
- A normal third-party app cannot silently switch mobile data or Wi-Fi on modern Android. Rescue Mode opens the official Internet panel instead.
- A foreground notification remains visible while screen sharing is active.
- Nearby Wi-Fi Direct can require Android's Nearby devices permission and, on some versions/OEMs, Location services to be enabled even though SimpleLink does not use Wi-Fi data for physical location.

## Project layout

```text
SimpleLink/
├── app/                         Android app
│   ├── src/main/java/...        UI, session, WebRTC, Wi-Fi Direct, rescue, control
│   ├── src/main/res/...         theme, icons, AccessibilityService config
│   └── src/test/...             protocol/session-code tests
├── server/                      signaling + TURN deployment
│   ├── src/server.js            WebSocket signaling server
│   ├── test/server.test.js      signaling flow test
│   ├── docker-compose.yml       Caddy + signaling + coturn
│   └── .env.example
├── gradlew                      checksum-verified Gradle bootstrap for Linux/macOS
├── gradlew.bat                  Gradle bootstrap for Windows
└── local.properties.example
```

## Requirements

- Android Studio with Android SDK 36
- JDK 17 or newer
- Android 8.0+ device (`minSdk 26`)
- Two physical Android phones for Wi-Fi Direct and realistic screen-control testing
- Google Play services for automatic Rescue SMS retrieval; the embedded `simplelink://` link is the manual offline fallback

## Build

Copy the local properties template and set your SDK path:

```bash
cp local.properties.example local.properties
```

For nearby/offline testing, the signaling URL can remain unset.

For internet testing, add this to `local.properties`:

```properties
simplelink.signalingUrl=ws://YOUR_LAN_IP:8787/ws
```

Debug builds permit local `ws://`. Release builds require secure `wss://`.

Build and run tests:

```bash
./gradlew test assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install the same APK on both phones so Rescue SMS app hashes match:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Local signaling test

For two phones that can reach the development computer:

```bash
cd server
npm install
PORT=8787 npm test
PORT=8787 npm start
```

Then compile the app with:

```properties
simplelink.signalingUrl=ws://YOUR_COMPUTER_LAN_IP:8787/ws
```

A TURN server is normally unnecessary for same-LAN testing.

## Internet deployment

The included Docker Compose stack runs:

- the SimpleLink signaling server,
- Caddy for automatic HTTPS/WSS,
- coturn for TURN fallback when direct WebRTC NAT traversal fails.

Create the deployment config:

```bash
cd server
cp .env.example .env
openssl rand -hex 32
```

Put the generated value in `TURN_SECRET`. Point `SIGNAL_DOMAIN` and `TURN_DOMAIN` DNS records to the server and set `TURN_EXTERNAL_IP` to its public IP. Open TCP 80/443, TCP+UDP 3478, and UDP 49160-49200.

Start it:

```bash
docker compose up -d --build
```

Then build the Android app with, for example:

```properties
simplelink.signalingUrl=wss://signal.yourdomain.com/ws
```

TURN credentials are short-lived HMAC credentials generated by the signaling server. The TURN secret is never shipped inside the Android app.

## Test matrix

Before calling a release production-ready, test on at least two real phones:

- Online -> online: code, approval, screen stream, taps, long-press, swipes, text entry, Back/Home/Recents, rotation, disconnect/reconnect.
- Offline nearby -> offline nearby: Wi-Fi Direct discovery, approval, stream/control.
- Target internet off -> helper online: Rescue SMS received automatically, or fallback rescue link; target Allow; Internet panel; internet enabled; automatic reconnect; MediaProjection consent; remote control.
- Deny paths: normal request denied, rescue denied, screen-capture denied.
- Permission paths: Accessibility disabled/enabled, Nearby Wi-Fi permission denied/allowed, notification permission denied/allowed.
- Network paths: Wi-Fi, mobile data, restrictive NAT requiring TURN, temporary disconnect.

## Implementation status

All 12 planned implementation stages are represented in source:

1. Android project foundation
2. Locked minimal UI/UX
3. Temporary 6-digit session codes
4. Connection/session engine
5. Nearby offline Wi-Fi Direct path
6. Internet signaling + WebRTC/TURN path
7. MediaProjection screen streaming
8. Accessibility gesture control
9. Explicit approval and Android permission flow
10. Rescue SMS + Internet panel + automatic reconnect
11. Security/reliability hardening
12. Tests, deployment files, cleanup, and packaging

The source in this ZIP has been syntax/static-checked, Android XML and deployment YAML were parsed, Node server files passed `node --check`, and the pure Kotlin session/rescue protocol tests were executed locally (including a 10,000-code generation round-trip and the 140-byte SMS limit). This build environment does not contain the Android SDK and cannot download Gradle/Maven/npm dependencies, so the Android APK and WebSocket integration tests could not be executed here. Run `./gradlew test assembleDebug` on a machine with Android SDK 36, then run the two-phone matrix above before treating a build as release-ready.
