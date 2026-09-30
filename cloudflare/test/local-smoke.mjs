import { WebSocket } from '../../server/node_modules/ws/wrapper.mjs';

function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
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

function next(ws, timeoutMs = 3000) {
  if (ws.q.length) return Promise.resolve(ws.q.shift());
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('message timeout')), timeoutMs);
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

const code = String(Math.floor(100000 + Math.random() * 900000));
const suffix = crypto.randomUUID();
const requestId = `worker-${suffix}`;
const hostKey = `host-${suffix}`;
const viewerKey = `viewer-${suffix}`;
const baseUrl = process.env.SIMPLELINK_TEST_WS || 'ws://127.0.0.1:8791/ws';
const url = `${baseUrl}?code=${code}`;

let host = await open(url);
let viewer = await open(url);

expect(await next(host), 'ice_config');
expect(await next(viewer), 'ice_config');

host.send(JSON.stringify({ type: 'register', code, hostKey }));
expect(await next(host), 'registered');

viewer.send(JSON.stringify({ type: 'join', code, requestId, viewerKey }));
expect(await next(host), 'incoming_request');
expect(await next(viewer), 'request_sent');

host.send(JSON.stringify({ type: 'approve', requestId }));
expect(await next(viewer), 'approved');

viewer.send(JSON.stringify({
  type: 'signal',
  requestId,
  payload: { kind: 'restart_request' }
}));
let relayed = expect(await next(host), 'signal');
if (relayed.payload.kind !== 'restart_request') {
  throw new Error('restart request relay failed');
}

viewer.send(JSON.stringify({
  type: 'signal',
  requestId,
  payload: { kind: 'offer', sdp: 'initial' }
}));
relayed = expect(await next(host), 'signal');
if (relayed.payload.sdp !== 'initial') throw new Error('initial relay failed');

viewer.terminate();
await new Promise(resolve => setTimeout(resolve, 150));

const viewer2 = await open(url);
expect(await next(viewer2), 'ice_config');
viewer2.send(JSON.stringify({ type: 'join', code, requestId, viewerKey }));
expect(await next(viewer2), 'request_sent');
expect(await next(viewer2), 'approved');

viewer2.send(JSON.stringify({
  type: 'signal',
  requestId,
  payload: { kind: 'candidate', candidate: 'viewer-reconnected' }
}));
relayed = expect(await next(host), 'signal');
if (relayed.payload.candidate !== 'viewer-reconnected') {
  throw new Error('viewer reconnect relay failed');
}

host.terminate();
await new Promise(resolve => setTimeout(resolve, 150));

const host2 = await open(url);
expect(await next(host2), 'ice_config');
host2.send(JSON.stringify({ type: 'register', code, hostKey }));
expect(await next(host2), 'registered');

viewer2.send(JSON.stringify({
  type: 'signal',
  requestId,
  payload: { kind: 'candidate', candidate: 'host-reconnected' }
}));
relayed = expect(await next(host2), 'signal');
if (relayed.payload.candidate !== 'host-reconnected') {
  throw new Error('host reconnect relay failed');
}

host2.send(JSON.stringify({
  type: 'signal',
  requestId,
  payload: { kind: 'answer', sdp: 'after-host-reconnect' }
}));
relayed = expect(await next(viewer2), 'signal');
if (relayed.payload.sdp !== 'after-host-reconnect') {
  throw new Error('reverse relay after host reconnect failed');
}

host2.close();
viewer2.close();

console.log('PASS code-sharding + approval + ICE-restart request + viewer reconnect + host reconnect + bidirectional signaling');
setTimeout(() => process.exit(0), 100);
