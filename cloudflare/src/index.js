const ROOM_TTL_MS = 5 * 60 * 1000;
const REQUEST_TTL_MS = 5 * 60 * 1000;
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const HOST_RECONNECT_GRACE_MS = 5 * 60 * 1000;
const VIEWER_RECONNECT_GRACE_MS = 60 * 1000;
const MAX_JOINS_PER_MINUTE = 20;
const MAX_QUEUED_SIGNALS = 64;
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

    const id = env.SIGNAL.idFromName(code);
    const headers = new Headers(request.headers);
    headers.set("x-simplelink-code", code);
    headers.set("x-simplelink-ip", request.headers.get("CF-Connecting-IP") || "unknown");
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

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const attachment = {
      id: crypto.randomUUID(),
      code,
      remoteKey: request.headers.get("x-simplelink-ip") || "unknown",
      role: "unknown",
      hostKey: null,
      requestId: null,
      viewerKey: null
    };

    server.serializeAttachment(attachment);
    this.ctx.acceptWebSocket(server, ["client"]);
    sendJson(server, await createIceConfig(this.env));

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
      "https://simplelink-rate-limit/check",
      { method: "POST" }
    );
    return response.ok;
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
    await this.clean();

    let msg;
    try {
      msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
    } catch {
      sendJson(ws, { type: "error", reason: "bad_json" });
      return;
    }

    const meta = this.attachment(ws);
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

      const room = await this.ctx.storage.get("room");
      const host = room?.hostKey ? this.findHost(room.hostKey) : null;
      if (!room || room.expiresAt <= Date.now() || !host) {
        sendJson(ws, { type: "not_found" });
        return;
      }

      const key = this.requestKey(requestId);
      let req = await this.ctx.storage.get(key);
      if (req) {
        if (req.viewerKey !== viewerKey || req.hostKey !== room.hostKey) {
          sendJson(ws, { type: "busy", reason: "request_id_in_use" });
          return;
        }
      } else {
        if (!(await this.allowJoin(meta.remoteKey || "unknown"))) {
          sendJson(ws, { type: "busy", reason: "rate_limited" });
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
      const key = this.requestKey(requestId);
      const req = await this.ctx.storage.get(key);
      if (!req || !req.approved) return;

      if (meta.role === "viewer" && meta.requestId === requestId && meta.viewerKey === req.viewerKey) {
        const host = this.findHost(req.hostKey);
        if (!sendJson(host, { type: "signal", requestId, payload: msg.payload || {} })) {
          this.queueSignal(req, "host", msg.payload || {});
          await this.ctx.storage.put(key, req);
        }
        return;
      }

      if (meta.role === "host" && meta.hostKey === req.hostKey) {
        const viewer = this.findViewer(requestId, req.viewerKey);
        if (!sendJson(viewer, { type: "signal", requestId, payload: msg.payload || {} })) {
          this.queueSignal(req, "viewer", msg.payload || {});
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

    const now = Date.now();
    let record = await this.ctx.storage.get("window");
    if (!record || record.windowStart + 60_000 <= now) {
      record = { windowStart: now, count: 0 };
    }

    if (record.count >= MAX_JOINS_PER_MINUTE) {
      return new Response("Rate limited", { status: 429 });
    }

    record.count += 1;
    await this.ctx.storage.put("window", record);
    await this.ctx.storage.setAlarm(record.windowStart + 60_000);
    return new Response(null, { status: 204 });
  }

  async alarm() {
    await this.ctx.storage.delete("window");
  }
}
