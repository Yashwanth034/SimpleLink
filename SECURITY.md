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

## Abuse Shield

The production Worker protects free signaling capacity before accepting expensive or persistent work:

- WebSocket upgrades and new join attempts use separate per-IP minute/hour limits.
- Wrong or expired six-digit code guesses consume the join-attempt budget; reconnects with the original request identity do not.
- A sharing code allows one helper session at a time and at most six simultaneous signaling sockets, including brief reconnect overlap.
- Unauthenticated idle sockets expire after 20 seconds.
- Signaling messages are capped at 96 KiB and 160 messages per 10-second window.
- WebRTC signaling payloads accept only bounded `offer`, `answer`, and `candidate` shapes.
- Queued ICE/signaling payloads are bounded.
- New valid sessions pass through a global UTC-day admission budget. The default production limit is 8,000 new sessions/day; approved sessions and legitimate reconnects bypass that admission counter.
- `ADMIT_NEW_SESSIONS=false` is an emergency circuit breaker for new sessions without deliberately terminating already-approved sessions.

These controls reduce application-level quota abuse, but they cannot make a public endpoint immune to a sufficiently distributed platform-level denial-of-service attack. Provider-level protections and usage monitoring are still required as the service grows.

## Deliberate non-goals

SimpleLink does not attempt to bypass Android permission dialogs, secure-window capture protections, Accessibility enablement, lock-screen security, device-owner restrictions, or operating-system networking restrictions.

## Secrets

Do not commit `server/.env`, `local.properties`, keystores, production signing files, or TURN secrets. The included `.env.example` and `local.properties.example` contain placeholders only.

For the Cloudflare Worker toolchain, use Node.js 22 or newer with the pinned Wrangler version in `cloudflare/package.json`. The fallback Node signaling server pins `ws` and includes a lockfile so dependency audits are reproducible.
