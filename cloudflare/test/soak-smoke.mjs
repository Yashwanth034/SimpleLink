import { WebSocket } from '../../server/node_modules/ws/wrapper.mjs';

const base = process.env.SIMPLELINK_TEST_WS || 'ws://127.0.0.1:8810/ws';
const sequentialSessions = Number(process.env.SIMPLELINK_SOAK_SESSIONS || 180);
const concurrentSessions = Number(process.env.SIMPLELINK_SOAK_CONCURRENT || 24);
const reconnectCycles = Number(process.env.SIMPLELINK_SOAK_RECONNECTS || 30);
const codeBase = Number(
  process.env.SIMPLELINK_SOAK_CODE_BASE ||
    (100000 + Math.floor(Math.random() * 700000))
);

function sessionCode(n) {
  return String(100000 + ((codeBase + n) % 800000)).padStart(6, '0');
}

function id(prefix, n) {
  return `${prefix}-${n}-${crypto.randomUUID()}`;
}

function fakeIp(n, side) {
  const value = n * 2 + side;
  const b = 1 + Math.floor(value / 250);
  const c = 1 + (value % 250);
  return `198.18.${b}.${c}`;
}

function open(code, ip) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}?code=${code}`, {
      headers: { 'CF-Connecting-IP': ip }
    });
    ws.q = [];
    ws.waiters = [];
    ws.on('message', data => {
      const message = JSON.parse(data.toString());
      const waiter = ws.waiters.shift();
      if (waiter) waiter(message);
      else ws.q.push(message);
    });
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

function next(ws, timeoutMs = 4000) {
  if (ws.q.length) return Promise.resolve(ws.q.shift());
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error('message timeout')),
      timeoutMs
    );
    ws.waiters.push(message => {
      clearTimeout(timer);
      resolve(message);
    });
  });
}

function expect(message, type) {
  if (message?.type !== type) {
    throw new Error(`expected ${type}, got ${JSON.stringify(message)}`);
  }
  return message;
}

function terminate(ws) {
  try { ws?.terminate(); } catch {}
}

function close(ws) {
  try { ws?.close(); } catch {}
}

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 8));
}

async function fullSession(n, options = {}) {
  const code = sessionCode(n);
  const hostKey = id('host', n);
  const viewerKey = id('viewer', n);
  const requestId = id('request', n);
  const hostIp = fakeIp(n, 0);
  const viewerIp = fakeIp(n, 1);

  let host = await open(code, hostIp);
  let viewer = await open(code, viewerIp);
  expect(await next(host), 'ice_config');
  expect(await next(viewer), 'ice_config');

  host.send(JSON.stringify({ type: 'register', code, hostKey }));
  expect(await next(host), 'registered');

  viewer.send(JSON.stringify({
    type: 'join',
    code,
    requestId,
    viewerKey
  }));
  expect(await next(host), 'incoming_request');
  expect(await next(viewer), 'request_sent');

  host.send(JSON.stringify({ type: 'approve', requestId }));
  expect(await next(viewer), 'approved');

  viewer.send(JSON.stringify({
    type: 'signal',
    requestId,
    payload: { kind: 'restart_request' }
  }));
  expect(await next(host), 'signal');

  viewer.send(JSON.stringify({
    type: 'signal',
    requestId,
    payload: { kind: 'offer', sdp: `offer-${n}` }
  }));
  const offer = expect(await next(host), 'signal');
  if (offer.payload.sdp !== `offer-${n}`) throw new Error('offer mismatch');

  host.send(JSON.stringify({
    type: 'signal',
    requestId,
    payload: { kind: 'answer', sdp: `answer-${n}` }
  }));
  const answer = expect(await next(viewer), 'signal');
  if (answer.payload.sdp !== `answer-${n}`) throw new Error('answer mismatch');

  viewer.send(JSON.stringify({
    type: 'signal',
    requestId,
    payload: {
      kind: 'candidate',
      candidate: `candidate-${n}`,
      sdpMid: '0',
      sdpMLineIndex: 0
    }
  }));
  expect(await next(host), 'signal');

  if (options.viewerReconnect) {
    terminate(viewer);
    await settle();
    viewer = await open(code, viewerIp);
    expect(await next(viewer), 'ice_config');
    viewer.send(JSON.stringify({ type: 'join', code, requestId, viewerKey }));
    expect(await next(viewer), 'request_sent');
    expect(await next(viewer), 'approved');

    viewer.send(JSON.stringify({
      type: 'signal',
      requestId,
      payload: {
        kind: 'candidate',
        candidate: `viewer-reconnect-${n}`
      }
    }));
    expect(await next(host), 'signal');
  }

  if (options.hostReconnect) {
    terminate(host);
    await settle();
    host = await open(code, hostIp);
    expect(await next(host), 'ice_config');
    host.send(JSON.stringify({ type: 'register', code, hostKey }));
    expect(await next(host), 'registered');

    viewer.send(JSON.stringify({
      type: 'signal',
      requestId,
      payload: {
        kind: 'candidate',
        candidate: `host-reconnect-${n}`
      }
    }));
    expect(await next(host), 'signal');
  }

  close(host);
  close(viewer);
}

async function repeatedReconnectSoak() {
  const n = 700001;
  const code = sessionCode(n);
  const hostKey = id('host-loop', n);
  const viewerKey = id('viewer-loop', n);
  const requestId = id('request-loop', n);
  const loopSubnet = 20 + (codeBase % 200);
  const hostIp = `198.19.${loopSubnet}.10`;
  const viewerIp = `198.19.${loopSubnet}.11`;

  let host = await open(code, hostIp);
  let viewer = await open(code, viewerIp);
  expect(await next(host), 'ice_config');
  expect(await next(viewer), 'ice_config');

  host.send(JSON.stringify({ type: 'register', code, hostKey }));
  expect(await next(host), 'registered');
  viewer.send(JSON.stringify({ type: 'join', code, requestId, viewerKey }));
  expect(await next(host), 'incoming_request');
  expect(await next(viewer), 'request_sent');
  host.send(JSON.stringify({ type: 'approve', requestId }));
  expect(await next(viewer), 'approved');

  for (let i = 0; i < reconnectCycles; i += 1) {
    if (i % 2 === 0) {
      terminate(viewer);
      await settle();
      viewer = await open(code, viewerIp);
      expect(await next(viewer), 'ice_config');
      viewer.send(JSON.stringify({ type: 'join', code, requestId, viewerKey }));
      expect(await next(viewer), 'request_sent');
      expect(await next(viewer), 'approved');
      viewer.send(JSON.stringify({
        type: 'signal',
        requestId,
        payload: {
          kind: 'candidate',
          candidate: `loop-viewer-${i}`
        }
      }));
      expect(await next(host), 'signal');
    } else {
      terminate(host);
      await settle();
      host = await open(code, hostIp);
      expect(await next(host), 'ice_config');
      host.send(JSON.stringify({ type: 'register', code, hostKey }));
      expect(await next(host), 'registered');
      viewer.send(JSON.stringify({
        type: 'signal',
        requestId,
        payload: {
          kind: 'candidate',
          candidate: `loop-host-${i}`
        }
      }));
      expect(await next(host), 'signal');
    }
  }

  close(host);
  close(viewer);
}

const started = Date.now();

for (let i = 0; i < sequentialSessions; i += 1) {
  await fullSession(i + 1, {
    viewerReconnect: i % 7 === 0,
    hostReconnect: i % 11 === 0
  });
  if ((i + 1) % 30 === 0) {
    console.log(`SOAK sequential ${i + 1}/${sequentialSessions}`);
  }
}

const batchBase = 500000;
await Promise.all(
  Array.from({ length: concurrentSessions }, (_, i) =>
    fullSession(batchBase + i)
  )
);
console.log(`SOAK concurrent ${concurrentSessions}/${concurrentSessions}`);

await repeatedReconnectSoak();
console.log(`SOAK reconnect cycles ${reconnectCycles}/${reconnectCycles}`);

const elapsedMs = Date.now() - started;
const memory = process.memoryUsage();
console.log(
  'PASS soak',
  JSON.stringify({
    sequentialSessions,
    concurrentSessions,
    reconnectCycles,
    elapsedMs,
    harnessHeapUsedBytes: memory.heapUsed,
    harnessRssBytes: memory.rss
  })
);

setTimeout(() => process.exit(0), 100);
