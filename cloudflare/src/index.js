const ROOM_TTL_MS = 5 * 60 * 1000;
const REQUEST_TTL_MS = 5 * 60 * 1000;
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const HOST_RECONNECT_GRACE_MS = 5 * 60 * 1000;
const VIEWER_RECONNECT_GRACE_MS = 60 * 1000;
const UNKNOWN_SOCKET_TTL_MS = 20 * 1000;
const MESSAGE_WINDOW_MS = 10 * 1000;
const MAX_CONNECTIONS_PER_MINUTE = 30;
const MAX_CONNECTIONS_PER_HOUR = 180;
const MAX_JOINS_PER_MINUTE = 12;
const MAX_JOINS_PER_HOUR = 60;
const MAX_SOCKETS_PER_CODE = 6;
const MAX_MESSAGES_PER_WINDOW = 160;
const MAX_MESSAGE_BYTES = 96 * 1024;
const MAX_SDP_CHARS = 64 * 1024;
const MAX_CANDIDATE_CHARS = 8 * 1024;
const MAX_QUEUED_SIGNALS = 48;
const DEFAULT_DAILY_NEW_SESSION_LIMIT = 8_000;
const TURN_TTL_SECONDS = 12 * 60 * 60;

function sendJson(ws, payload) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  try {
    ws.send(JSON.stringify(payload));
    return true;
  } catch {
    return false;
  }
}

function messageSize(raw) {
  if (typeof raw === "string") return new TextEncoder().encode(raw).byteLength;
  if (raw instanceof ArrayBuffer) return raw.byteLength;
  if (ArrayBuffer.isView(raw)) return raw.byteLength;
  return MAX_MESSAGE_BYTES + 1;
}

function validSignalPayload(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return false;
  const kind = payload.kind;
  if (kind === "offer" || kind === "answer") {
    return typeof payload.sdp === "string" && payload.sdp.length <= MAX_SDP_CHARS;
  }
  if (kind === "candidate") {
    return (
      typeof payload.candidate === "string" &&
      payload.candidate.length <= MAX_CANDIDATE_CHARS &&
      (payload.sdpMid === undefined ||
        (typeof payload.sdpMid === "string" && payload.sdpMid.length <= 128)) &&
      (payload.sdpMLineIndex === undefined ||
        (Number.isInteger(payload.sdpMLineIndex) &&
          payload.sdpMLineIndex >= 0 &&
          payload.sdpMLineIndex <= 128))
    );
  }
  if (kind === "restart_request") {
    return Object.keys(payload).every(key => key === "kind");
  }
  return false;
}

function positiveInt(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

async function createOpenRelayIceConfig() {
  const expiresAt = Math.floor(Date.now() / 1000) + TURN_TTL_SECONDS;
  const username = `${expiresAt}:simplelink`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode("openrelayprojectsecret"),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(username)
  );
  const credential = btoa(String.fromCharCode(...new Uint8Array(signature)));

  return {
    type: "ice_config",
    servers: [
      { urls: ["stun:stun.cloudflare.com:3478"] },
      { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
      {
        urls: [
          "turn:staticauth.openrelay.metered.ca:80?transport=udp",
          "turn:staticauth.openrelay.metered.ca:80?transport=tcp",
          "turn:staticauth.openrelay.metered.ca:443?transport=udp",
          "turn:staticauth.openrelay.metered.ca:443?transport=tcp",
          "turns:staticauth.openrelay.metered.ca:443?transport=tcp"
        ],
        username,
        credential
      }
    ]
  };
}

async function createIceConfig(env) {
  const freeRelay = await createOpenRelayIceConfig();

  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return freeRelay;

  try {
    const response = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ ttl: TURN_TTL_SECONDS })
      }
    );
    if (!response.ok) return freeRelay;
    const body = await response.json();
    return Array.isArray(body?.iceServers) && body.iceServers.length
      ? { type: "ice_config", servers: body.iceServers }
      : freeRelay;
  } catch {
    return freeRelay;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("ok", {
        status: 200,
        headers: { "content-type": "text/plain; charset=utf-8" }
      });
    }

    if (url.pathname !== "/ws") return new Response("Not found", { status: 404 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }

    const code = url.searchParams.get("code") || "";
    if (!/^\d{6}$/.test(code)) return new Response("Invalid code", { status: 400 });

    const remoteKey = request.headers.get("CF-Connecting-IP") || "unknown";
    const limiterId = env.RATE_LIMIT.idFromName(remoteKey);
    const admission = await env.RATE_LIMIT.get(limiterId).fetch(
      "https://simplelink-rate-limit/connect",
      { method: "POST" }
    );
    if (!admission.ok) {
      return new Response("Too many connection attempts", {
        status: 429,
        headers: { "Retry-After": admission.headers.get("Retry-After") || "60" }
      });
    }

    const id = env.SIGNAL.idFromName(code);
    const headers = new Headers(request.headers);
    headers.set("x-simplelink-code", code);
    headers.set("x-simplelink-ip", remoteKey);
    return env.SIGNAL.get(id).fetch(new Request(request, { headers }));
  }
};

export class SignalHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }

    const code = request.headers.get("x-simplelink-code") || "";
    if (!/^\d{6}$/.test(code)) return new Response("Invalid code", { status: 400 });

    if (this.openClients().length >= MAX_SOCKETS_PER_CODE) {
      return new Response("Session socket limit reached", {
        status: 429,
        headers: { "Retry-After": "30" }
      });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const now = Date.now();
    const attachment = {
      id: crypto.randomUUID(),
      code,
      remoteKey: request.headers.get("x-simplelink-ip") || "unknown",
      role: "unknown",
      hostKey: null,
      requestId: null,
      viewerKey: null,
      connectedAt: now,
      messageWindowStart: now,
      messageCount: 0
    };

    server.serializeAttachment(attachment);
    this.ctx.acceptWebSocket(server, ["client"]);
    sendJson(server, await createIceConfig(this.env));
    await this.scheduleAlarm();

    return new Response(null, { status: 101, webSocket: client });
  }

  attachment(ws) {
    return ws.deserializeAttachment() || {};
  }

  saveAttachment(ws, patch) {
    const next = { ...this.attachment(ws), ...patch };
    ws.serializeAttachment(next);
    return next;
  }

  openClients() {
    return this.ctx
      .getWebSockets("client")
      .filter(ws => ws.readyState === WebSocket.OPEN);
  }

  findHost(hostKey) {
    return this.openClients().find(ws => {
      const item = this.attachment(ws);
      return item.role === "host" && item.hostKey === hostKey;
    });
  }

  findViewer(requestId, viewerKey) {
    return this.openClients().find(ws => {
      const item = this.attachment(ws);
      return (
        item.role === "viewer" &&
        item.requestId === requestId &&
        item.viewerKey === viewerKey
      );
    });
  }

  requestKey(requestId) {
    return `request:${requestId}`;
  }

  async requestEntries() {
    return this.ctx.storage.list({ prefix: "request:" });
  }

  async clean() {
    const now = Date.now();

    for (const ws of this.openClients()) {
      const meta = this.attachment(ws);
      if (
        meta.role === "unknown" &&
        Number.isFinite(meta.connectedAt) &&
        meta.connectedAt + UNKNOWN_SOCKET_TTL_MS <= now
      ) {
        try {
          ws.close(1008, "handshake_timeout");
        } catch {}
      }
    }

    const room = await this.ctx.storage.get("room");
    if (room?.expiresAt <= now) await this.ctx.storage.delete("room");

    const entries = await this.requestEntries();
    const deletes = [];
    for (const [key, req] of entries) {
      if (req.expiresAt <= now) {
        deletes.push(key);
        continue;
      }

      if (
        req.hostDisconnectedAt &&
        req.hostDisconnectedAt + HOST_RECONNECT_GRACE_MS <= now
      ) {
        const viewer = this.findViewer(req.requestId, req.viewerKey);
        sendJson(viewer, { type: "peer_left", requestId: req.requestId });
        deletes.push(key);
        continue;
      }

      if (
        req.viewerDisconnectedAt &&
        req.viewerDisconnectedAt + VIEWER_RECONNECT_GRACE_MS <= now
      ) {
        const host = this.findHost(req.hostKey);
        sendJson(host, { type: "peer_left", requestId: req.requestId });
        deletes.push(key);
      }
    }

    if (deletes.length) await this.ctx.storage.delete(deletes);
    await this.scheduleAlarm();
  }

  async scheduleAlarm() {
    const now = Date.now();
    const deadlines = [];
    const room = await this.ctx.storage.get("room");
    if (room?.expiresAt > now) deadlines.push(room.expiresAt);

    const entries = await this.requestEntries();
    for (const req of entries.values()) {
      if (req.expiresAt > now) deadlines.push(req.expiresAt);
      if (req.hostDisconnectedAt) {
        deadlines.push(req.hostDisconnectedAt + HOST_RECONNECT_GRACE_MS);
      }
      if (req.viewerDisconnectedAt) {
        deadlines.push(req.viewerDisconnectedAt + VIEWER_RECONNECT_GRACE_MS);
      }
    }

    for (const ws of this.openClients()) {
      const meta = this.attachment(ws);
      if (
        meta.role === "unknown" &&
        Number.isFinite(meta.connectedAt) &&
        meta.connectedAt + UNKNOWN_SOCKET_TTL_MS > now
      ) {
        deadlines.push(meta.connectedAt + UNKNOWN_SOCKET_TTL_MS);
      }
    }

    const next = deadlines.filter(value => value > now).sort((a, b) => a - b)[0];
    if (next) await this.ctx.storage.setAlarm(next);
    else {
      const current = await this.ctx.storage.getAlarm();
      if (current !== null) await this.ctx.storage.deleteAlarm();
    }
  }

  async allowJoin(key) {
    const id = this.env.RATE_LIMIT.idFromName(key);
    const response = await this.env.RATE_LIMIT.get(id).fetch(
      "https://simplelink-rate-limit/join",
      { method: "POST" }
    );
    return response.ok;
  }

  async allowNewSession() {
    if (String(this.env.ADMIT_NEW_SESSIONS || "true").toLowerCase() === "false") {
      return { allowed: false, reason: "admission_paused" };
    }

    const id = this.env.ADMISSION.idFromName("global");
    const response = await this.env.ADMISSION.get(id).fetch(
      "https://simplelink-admission/admit",
      { method: "POST" }
    );
    return {
      allowed: response.ok,
      reason: response.ok ? null : "daily_capacity"
    };
  }

  queueSignal(req, target, payload) {
    const key = target === "host" ? "pendingToHost" : "pendingToViewer";
    const values = Array.isArray(req[key]) ? req[key] : [];
    values.push(payload);
    req[key] = values.slice(-MAX_QUEUED_SIGNALS);
  }

  flushSignals(ws, requestId, req, target) {
    const key = target === "host" ? "pendingToHost" : "pendingToViewer";
    const values = Array.isArray(req[key]) ? req[key] : [];
    for (const payload of values) {
      sendJson(ws, { type: "signal", requestId, payload });
    }
    req[key] = [];
  }

  async webSocketMessage(ws, raw) {
    if (messageSize(raw) > MAX_MESSAGE_BYTES) {
      try {
        ws.close(1009, "message_too_large");
      } catch {}
      return;
    }

    const now = Date.now();
    let meta = this.attachment(ws);
    if (
      !Number.isFinite(meta.messageWindowStart) ||
      meta.messageWindowStart + MESSAGE_WINDOW_MS <= now
    ) {
      meta = this.saveAttachment(ws, {
        messageWindowStart: now,
        messageCount: 1
      });
    } else {
      const nextCount = (meta.messageCount || 0) + 1;
      meta = this.saveAttachment(ws, { messageCount: nextCount });
      if (nextCount > MAX_MESSAGES_PER_WINDOW) {
        try {
          ws.close(1008, "message_rate_limited");
        } catch {}
        return;
      }
    }

    await this.clean();

    let msg;
    try {
      msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
    } catch {
      sendJson(ws, { type: "error", reason: "bad_json" });
      return;
    }

    meta = this.attachment(ws);
    const code = String(msg.code || meta.code || "");
    if (code !== meta.code || !/^\d{6}$/.test(code)) {
      sendJson(ws, { type: "error", reason: "bad_code" });
      return;
    }

    if (msg.type === "register") {
      const hostKey = String(msg.hostKey || "");
      if (!/^[A-Za-z0-9._:-]{16,128}$/.test(hostKey)) {
        sendJson(ws, { type: "error", reason: "bad_host_key" });
        return;
      }

      const room = await this.ctx.storage.get("room");
      if (room && room.expiresAt > Date.now() && room.hostKey !== hostKey) {
        sendJson(ws, { type: "busy", reason: "code_in_use" });
        return;
      }

      const entries = await this.requestEntries();
      for (const req of entries.values()) {
        if (req.expiresAt > Date.now() && req.hostKey !== hostKey) {
          sendJson(ws, { type: "busy", reason: "code_in_use" });
          return;
        }
      }

      const previousHost = this.findHost(hostKey);
      if (previousHost && previousHost !== ws) {
        try {
          previousHost.close(1012, "replaced");
        } catch {}
      }

      this.saveAttachment(ws, {
        role: "host",
        hostKey,
        requestId: null,
        viewerKey: null
      });

      await this.ctx.storage.put("room", {
        hostKey,
        expiresAt: Date.now() + ROOM_TTL_MS
      });

      for (const [key, req] of entries) {
        if (req.hostKey !== hostKey || req.expiresAt <= Date.now()) continue;
        req.hostDisconnectedAt = null;
        this.flushSignals(ws, req.requestId, req, "host");
        await this.ctx.storage.put(key, req);
        if (!req.approved && this.findViewer(req.requestId, req.viewerKey)) {
          sendJson(ws, { type: "incoming_request", requestId: req.requestId });
        }
      }

      await this.scheduleAlarm();
      sendJson(ws, { type: "registered", code });
      return;
    }

    if (msg.type === "join") {
      const requestId = String(msg.requestId || crypto.randomUUID());
      const viewerKey = String(msg.viewerKey || "");
      if (requestId.length < 8 || requestId.length > 80) {
        sendJson(ws, { type: "error", reason: "bad_request_id" });
        return;
      }
      if (!/^[A-Za-z0-9._:-]{16,128}$/.test(viewerKey)) {
        sendJson(ws, { type: "error", reason: "bad_viewer_key" });
        return;
      }

      const key = this.requestKey(requestId);
      let req = await this.ctx.storage.get(key);

      // A legitimate reconnect keeps its original request identity and bypasses
      // new-session throttles. Every genuinely new join attempt is counted,
      // including guesses for codes that do not exist.
      if (!req && !(await this.allowJoin(meta.remoteKey || "unknown"))) {
        sendJson(ws, { type: "busy", reason: "rate_limited" });
        return;
      }

      const room = await this.ctx.storage.get("room");
      const host = room?.hostKey ? this.findHost(room.hostKey) : null;
      if (!room || room.expiresAt <= Date.now() || !host) {
        sendJson(ws, { type: "not_found" });
        return;
      }

      if (req) {
        if (req.viewerKey !== viewerKey || req.hostKey !== room.hostKey) {
          sendJson(ws, { type: "busy", reason: "request_id_in_use" });
          return;
        }
      } else {
        const activeRequests = await this.requestEntries();
        if ([...activeRequests.values()].some(item => item.expiresAt > Date.now())) {
          sendJson(ws, { type: "busy", reason: "session_busy" });
          return;
        }

        const admission = await this.allowNewSession();
        if (!admission.allowed) {
          sendJson(ws, { type: "busy", reason: admission.reason });
          return;
        }

        req = {
          requestId,
          hostKey: room.hostKey,
          viewerKey,
          approved: false,
          expiresAt: Date.now() + REQUEST_TTL_MS,
          hostDisconnectedAt: null,
          viewerDisconnectedAt: null,
          pendingToHost: [],
          pendingToViewer: []
        };
      }

      const previousViewer = this.findViewer(requestId, viewerKey);
      if (previousViewer && previousViewer !== ws) {
        try {
          previousViewer.close(1012, "replaced");
        } catch {}
      }

      this.saveAttachment(ws, {
        role: "viewer",
        requestId,
        viewerKey,
        hostKey: null
      });

      req.viewerDisconnectedAt = null;
      req.expiresAt = Date.now() + (req.approved ? SESSION_TTL_MS : REQUEST_TTL_MS);
      this.flushSignals(ws, requestId, req, "viewer");
      await this.ctx.storage.put(key, req);
      await this.scheduleAlarm();

      sendJson(ws, { type: "request_sent", requestId });
      if (req.approved) {
        sendJson(ws, { type: "approved", requestId });
      } else {
        sendJson(host, { type: "incoming_request", requestId });
      }
      return;
    }

    if (msg.type === "approve" || msg.type === "reject") {
      if (meta.role !== "host" || !meta.hostKey) return;

      const requestId = String(msg.requestId || "");
      const key = this.requestKey(requestId);
      const req = await this.ctx.storage.get(key);
      if (!req || req.hostKey !== meta.hostKey) return;

      const viewer = this.findViewer(requestId, req.viewerKey);
      if (msg.type === "reject") {
        sendJson(viewer, { type: "rejected", requestId });
        await this.ctx.storage.delete(key);
        await this.scheduleAlarm();
        return;
      }

      req.approved = true;
      req.hostDisconnectedAt = null;
      req.expiresAt = Date.now() + SESSION_TTL_MS;
      await this.ctx.storage.put(key, req);
      await this.scheduleAlarm();
      sendJson(viewer, { type: "approved", requestId });
      return;
    }

    if (msg.type === "signal") {
      const requestId = String(msg.requestId || "");
      const payload = msg.payload;
      if (!validSignalPayload(payload)) {
        sendJson(ws, { type: "error", reason: "bad_signal" });
        return;
      }

      const key = this.requestKey(requestId);
      const req = await this.ctx.storage.get(key);
      if (!req || !req.approved) return;

      if (meta.role === "viewer" && meta.requestId === requestId && meta.viewerKey === req.viewerKey) {
        const host = this.findHost(req.hostKey);
        if (!sendJson(host, { type: "signal", requestId, payload })) {
          this.queueSignal(req, "host", payload);
          await this.ctx.storage.put(key, req);
        }
        return;
      }

      if (meta.role === "host" && meta.hostKey === req.hostKey) {
        const viewer = this.findViewer(requestId, req.viewerKey);
        if (!sendJson(viewer, { type: "signal", requestId, payload })) {
          this.queueSignal(req, "viewer", payload);
          await this.ctx.storage.put(key, req);
        }
      }
    }
  }

  async markDisconnected(ws) {
    const meta = this.attachment(ws);
    const now = Date.now();

    if (meta.role === "viewer" && meta.requestId) {
      const key = this.requestKey(meta.requestId);
      const req = await this.ctx.storage.get(key);
      if (req && req.viewerKey === meta.viewerKey) {
        req.viewerDisconnectedAt = now;
        await this.ctx.storage.put(key, req);
      }
    }

    if (meta.role === "host" && meta.hostKey) {
      const entries = await this.requestEntries();
      for (const [key, req] of entries) {
        if (req.hostKey !== meta.hostKey) continue;
        req.hostDisconnectedAt = now;
        await this.ctx.storage.put(key, req);
      }
    }

    await this.scheduleAlarm();
  }

  async webSocketClose(ws) {
    await this.markDisconnected(ws);
  }

  async webSocketError(ws) {
    await this.markDisconnected(ws);
  }

  async alarm() {
    await this.clean();
  }
}

export class JoinLimiter {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch(request) {
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    const action = new URL(request.url).pathname.endsWith("/connect")
      ? "connect"
      : "join";
    const perMinute =
      action === "connect" ? MAX_CONNECTIONS_PER_MINUTE : MAX_JOINS_PER_MINUTE;
    const perHour =
      action === "connect" ? MAX_CONNECTIONS_PER_HOUR : MAX_JOINS_PER_HOUR;
    const blockMs = action === "connect" ? 60_000 : 2 * 60_000;
    const key = `window:${action}`;
    const now = Date.now();

    let record = (await this.ctx.storage.get(key)) || {
      minuteStart: now,
      minuteCount: 0,
      hourStart: now,
      hourCount: 0,
      blockedUntil: 0
    };

    if (record.blockedUntil > now) {
      return new Response("Rate limited", {
        status: 429,
        headers: {
          "Retry-After": String(
            Math.max(1, Math.ceil((record.blockedUntil - now) / 1000))
          )
        }
      });
    }

    if (record.minuteStart + 60_000 <= now) {
      record.minuteStart = now;
      record.minuteCount = 0;
    }
    if (record.hourStart + 60 * 60_000 <= now) {
      record.hourStart = now;
      record.hourCount = 0;
    }

    if (record.minuteCount >= perMinute || record.hourCount >= perHour) {
      record.blockedUntil = now + blockMs;
      await this.ctx.storage.put(key, record);
      await this.ctx.storage.setAlarm(now + 2 * 60 * 60_000);
      return new Response("Rate limited", {
        status: 429,
        headers: { "Retry-After": String(Math.ceil(blockMs / 1000)) }
      });
    }

    record.minuteCount += 1;
    record.hourCount += 1;
    record.blockedUntil = 0;
    await this.ctx.storage.put(key, record);
    await this.ctx.storage.setAlarm(now + 2 * 60 * 60_000);
    return new Response(null, { status: 204 });
  }

  async alarm() {
    await this.ctx.storage.deleteAll();
  }
}

export class AdmissionGuard {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    const now = new Date();
    const day = now.toISOString().slice(0, 10);
    const limit = positiveInt(
      this.env.DAILY_NEW_SESSION_LIMIT,
      DEFAULT_DAILY_NEW_SESSION_LIMIT,
      1,
      50_000
    );

    let record = await this.ctx.storage.get("daily");
    if (!record || record.day !== day) {
      record = { day, count: 0 };
    }

    if (record.count >= limit) {
      return new Response("Daily admission limit reached", {
        status: 429,
        headers: { "Retry-After": "3600" }
      });
    }

    record.count += 1;
    await this.ctx.storage.put("daily", record);

    const nextMidnight = Date.UTC(
      now.getUTCFullYear(),
      now.getUTCMonth(),
      now.getUTCDate() + 1,
      0,
      5,
      0
    );
    await this.ctx.storage.setAlarm(nextMidnight);
    return new Response(null, { status: 204 });
  }

  async alarm() {
    await this.ctx.storage.delete("daily");
  }
}
