import { WebSocket, WebSocketServer } from 'ws';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const PORT = Number(process.env.PORT || 8787);
const ROOM_TTL_MS = 5 * 60 * 1000;
const REQUEST_TTL_MS = 5 * 60 * 1000;
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const HOST_RECONNECT_GRACE_MS = REQUEST_TTL_MS;
const VIEWER_RECONNECT_GRACE_MS = 60 * 1000;
const MAX_JOINS_PER_MINUTE = 20;
const TURN_TTL_SECONDS = Number(process.env.TURN_TTL_SECONDS || 3600);
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_URLS = (process.env.TURN_URLS || '')
  .split(',')
  .map(value => value.trim())
  .filter(Boolean);

const rooms = new Map();
const requests = new Map();
const joinRate = new Map();

function now() {
  return Date.now();
}

function send(ws, payload) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload));
}

function iceConfig(clientId) {
  const servers = [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }
  ];
  if (TURN_SECRET && TURN_URLS.length) {
    const username = `${Math.floor(Date.now() / 1000) + TURN_TTL_SECONDS}:${clientId}`;
    const credential = crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64');
    servers.push({ urls: TURN_URLS, username, credential });
  }
  return { type: 'ice_config', servers };
}

function clean() {
  const t = now();
  for (const [code, room] of rooms) {
    if (room.expiresAt <= t || room.host.readyState !== WebSocket.OPEN) rooms.delete(code);
  }
  for (const [id, req] of requests) {
    if (req.expiresAt <= t) {
      requests.delete(id);
      continue;
    }
    if (
      req.viewer?.readyState !== WebSocket.OPEN &&
      req.viewerDisconnectedAt &&
      req.viewerDisconnectedAt + VIEWER_RECONNECT_GRACE_MS <= t
    ) {
      send(req.host, { type: 'peer_left', requestId: id });
      requests.delete(id);
      continue;
    }
    if (req.host?.readyState === WebSocket.OPEN) continue;
    if (req.hostDisconnectedAt && req.hostDisconnectedAt + HOST_RECONNECT_GRACE_MS <= t) {
      send(req.viewer, { type: 'peer_left', requestId: id });
      requests.delete(id);
    }
  }
  for (const [key, record] of joinRate) {
    if (record.windowStart + 60_000 <= t) joinRate.delete(key);
  }
}

function allowJoin(key) {
  const t = now();
  const record = joinRate.get(key);
  if (!record || record.windowStart + 60_000 <= t) {
    joinRate.set(key, { windowStart: t, count: 1 });
    return true;
  }
  if (record.count >= MAX_JOINS_PER_MINUTE) return false;
  record.count += 1;
  return true;
}

function removeSocket(ws) {
  for (const [code, room] of rooms) {
    if (room.host === ws) rooms.delete(code);
  }
  for (const req of requests.values()) {
    if (req.viewer === ws) {
      req.viewer = null;
      req.viewerDisconnectedAt = now();
    }
    if (req.host === ws) {
      req.host = null;
      req.hostDisconnectedAt = now();
    }
  }
}

export function createServer({ port = PORT } = {}) {
  const wss = new WebSocketServer({ port, path: '/ws', maxPayload: 256 * 1024 });

  wss.on('connection', (ws, req) => {
    ws.id = crypto.randomUUID();
    const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
    ws.remoteKey = forwarded || req.socket.remoteAddress || 'unknown';
    ws.alive = true;
    send(ws, iceConfig(ws.id));

    ws.on('pong', () => { ws.alive = true; });

    ws.on('message', raw => {
      clean();
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        send(ws, { type: 'error', reason: 'bad_json' });
        return;
      }

      if (msg.type === 'register') {
        const code = String(msg.code || '');
        const hostKey = String(msg.hostKey || '');
        if (!/^\d{6}$/.test(code)) return send(ws, { type: 'error', reason: 'bad_code' });
        if (!/^[A-Za-z0-9._:-]{16,128}$/.test(hostKey)) {
          return send(ws, { type: 'error', reason: 'bad_host_key' });
        }

        const existing = rooms.get(code);
        if (existing && existing.host !== ws && existing.expiresAt > now() && existing.hostKey !== hostKey) {
          return send(ws, { type: 'busy', reason: 'code_in_use' });
        }

        for (const req of requests.values()) {
          if (req.code === code && req.expiresAt > now() && req.hostKey !== hostKey) {
            return send(ws, { type: 'busy', reason: 'code_in_use' });
          }
        }

        for (const [registeredCode, room] of rooms) {
          if (room.host === ws && registeredCode !== code) rooms.delete(registeredCode);
        }

        rooms.set(code, { host: ws, hostKey, expiresAt: now() + ROOM_TTL_MS });

        for (const req of requests.values()) {
          if (req.code === code && req.hostKey === hostKey && req.viewer.readyState === WebSocket.OPEN) {
            req.host = ws;
            req.hostDisconnectedAt = null;
          }
        }

        return send(ws, { type: 'registered', code });
      }

      if (msg.type === 'join') {
        if (!allowJoin(ws.remoteKey)) return send(ws, { type: 'busy', reason: 'rate_limited' });
        const code = String(msg.code || '');
        if (!/^\d{6}$/.test(code)) return send(ws, { type: 'error', reason: 'bad_code' });
        const room = rooms.get(code);
        if (!room || room.expiresAt <= now()) return send(ws, { type: 'not_found' });
        const requestId = String(msg.requestId || crypto.randomUUID());
        if (requestId.length < 8 || requestId.length > 80) return send(ws, { type: 'error', reason: 'bad_request_id' });
        const viewerKey = String(msg.viewerKey || `legacy:${ws.id}`);
        const existingRequest = requests.get(requestId);

        if (existingRequest) {
          if (existingRequest.viewerKey !== viewerKey || existingRequest.code !== code) {
            return send(ws, { type: 'busy', reason: 'request_id_in_use' });
          }
          existingRequest.viewer = ws;
          existingRequest.viewerDisconnectedAt = null;
          existingRequest.expiresAt = now() + (existingRequest.approved ? SESSION_TTL_MS : REQUEST_TTL_MS);
          send(ws, { type: 'request_sent', requestId });
          if (existingRequest.approved) return send(ws, { type: 'approved', requestId });
          send(existingRequest.host, { type: 'incoming_request', requestId });
          return;
        }

        requests.set(requestId, {
          code,
          hostKey: room.hostKey,
          host: room.host,
          viewer: ws,
          viewerKey,
          hostDisconnectedAt: null,
          viewerDisconnectedAt: null,
          expiresAt: now() + REQUEST_TTL_MS,
          approved: false
        });
        send(room.host, { type: 'incoming_request', requestId });
        return send(ws, { type: 'request_sent', requestId });
      }

      if (msg.type === 'approve' || msg.type === 'reject') {
        const requestId = String(msg.requestId || '');
        const request = requests.get(requestId);
        if (!request) return;

        const room = rooms.get(request.code);
        const sameHostSession =
          request.host === ws ||
          (room?.host === ws && room.hostKey === request.hostKey);
        if (!sameHostSession) return;

        request.host = ws;
        request.hostDisconnectedAt = null;

        if (msg.type === 'reject') {
          send(request.viewer, { type: 'rejected', requestId });
          requests.delete(requestId);
          return;
        }
        request.approved = true;
        request.expiresAt = now() + SESSION_TTL_MS;
        return send(request.viewer, { type: 'approved', requestId });
      }

      if (msg.type === 'signal') {
        const requestId = String(msg.requestId || '');
        const request = requests.get(requestId);
        if (!request || !request.approved) return;

        const room = rooms.get(request.code);
        const currentHost =
          request.host?.readyState === WebSocket.OPEN
            ? request.host
            : room?.host === ws && room.hostKey === request.hostKey
              ? ws
              : room?.host && room.hostKey === request.hostKey
                ? room.host
                : null;

        let peer = null;
        if (request.viewer === ws) {
          peer = currentHost;
        } else if (
          request.host === ws ||
          (room?.host === ws && room.hostKey === request.hostKey)
        ) {
          request.host = ws;
          request.hostDisconnectedAt = null;
          peer = request.viewer;
        }

        if (!peer) return;
        return send(peer, { type: 'signal', requestId, payload: msg.payload || {} });
      }
    });

    ws.on('close', () => removeSocket(ws));
    ws.on('error', () => removeSocket(ws));
  });

  const cleanTimer = setInterval(clean, 30_000);
  cleanTimer.unref();

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.alive) {
        ws.terminate();
        continue;
      }
      ws.alive = false;
      ws.ping();
    }
  }, 30_000);
  heartbeat.unref();

  wss.on('close', () => {
    clearInterval(cleanTimer);
    clearInterval(heartbeat);
  });

  return wss;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createServer();
  process.stdout.write(`SimpleLink signaling listening on :${PORT}/ws\n`);
}
