# Security

SimpleLink is designed for explicitly approved remote-support sessions.

## Session protections

- Six-digit codes are generated with `SecureRandom` and exist only while the target is sharing.
- Internet join attempts are rate-limited by the signaling server.
- Every normal or rescued request requires target-side approval.
- Rescue SMS carries a request identifier but never grants control on its own.
- Media and control data use WebRTC DTLS/SRTP rather than passing through the signaling server.
- TURN credentials are short-lived and derived server-side from the TURN shared secret.
- Release builds do not allow cleartext networking.
- The target's MediaProjection foreground notification stays visible during screen sharing.

## Deliberate non-goals

SimpleLink does not attempt to bypass Android permission dialogs, secure-window capture protections, Accessibility enablement, lock-screen security, device-owner restrictions, or operating-system networking restrictions.

## Secrets

Do not commit `server/.env`, `local.properties`, keystores, production signing files, or TURN secrets. The included `.env.example` and `local.properties.example` contain placeholders only.
