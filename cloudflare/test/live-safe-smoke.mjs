import { WebSocket } from '../../server/node_modules/ws/wrapper.mjs';

const base = process.env.SIMPLELINK_TEST_WS;

if (!base) {
  throw new Error('SIMPLELINK_TEST_WS is required for live-safe smoke tests');
}

function code() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function key(prefix) {
  return `${prefix}-${crypto.randomUUID()}`;
}

function open(sessionCode) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}?code=${sessionCode}`);
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

function next(ws, timeoutMs = 5000) {
  if (ws.q.length) return Promise.resolve(ws.q.shift());
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('message timeout')), timeoutMs);
    ws.waiters.push(message => {
      clearTimeout(timer);
      resolve(message);
    });
  });
}

function close(ws) {
  try { ws.close(); } catch {}
}

async function rejectedOpen(sessionCode) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}?code=${sessionCode}`);
    let settled = false;
    ws.once('unexpected-response', (_request, response) => {
      settled = true;
      const status = response.statusCode;
      response.resume();
      resolve(status);
    });
    ws.once('error', error => {
      if (!settled && /429/.test(error.message)) {
        settled = true;
        resolve(429);
      } else if (!settled) {
        reject(error);
      }
    });
    ws.once('open', () => {
      if (!settled) {
        settled = true;
        ws.close();
        resolve(101);
      }
    });
  });
}

// One helper only, plus bad-signal validation.
{
  const c = code();
  const host = await open(c);
  const first = await open(c);
  const second = await open(c);
  await next(host); await next(first); await next(second);

  const hostKey = key('host');
  host.send(JSON.stringify({ type: 'register', code: c, hostKey }));
  if ((await next(host)).type !== 'registered') throw new Error('register failed');

  first.send(JSON.stringify({
    type: 'join',
    code: c,
    requestId: key('request'),
    viewerKey: key('viewer')
  }));
  if ((await next(host)).type !== 'incoming_request') throw new Error('request missing');
  if ((await next(first)).type !== 'request_sent') throw new Error('first helper failed');

  second.send(JSON.stringify({
    type: 'join',
    code: c,
    requestId: key('request'),
    viewerKey: key('viewer')
  }));
  const busy = await next(second);
  if (busy.type !== 'busy' || busy.reason !== 'session_busy') {
    throw new Error(`expected session_busy: ${JSON.stringify(busy)}`);
  }

  second.send(JSON.stringify({
    type: 'signal',
    code: c,
    requestId: key('request'),
    payload: { kind: 'invalid' }
  }));
  const bad = await next(second);
  if (bad.type !== 'error' || bad.reason !== 'bad_signal') {
    throw new Error(`bad signal not rejected: ${JSON.stringify(bad)}`);
  }

  close(host); close(first); close(second);
}

// Oversized payload closes with 1009.
{
  const c = code();
  const ws = await open(c);
  await next(ws);
  const closed = new Promise(resolve =>
    ws.once('close', (status, reason) =>
      resolve({ status, reason: reason.toString() })
    )
  );
  ws.send('x'.repeat(100 * 1024));
  const result = await closed;
  if (result.status !== 1009) {
    throw new Error(`oversized close code was ${result.status}`);
  }
}

// Per-code idle-socket cap rejects the seventh socket without exhausting IP limits.
{
  const c = code();
  const sockets = [];
  for (let i = 0; i < 6; i += 1) {
    const ws = await open(c);
    await next(ws);
    sockets.push(ws);
  }
  const status = await rejectedOpen(c);
  if (status !== 429) throw new Error(`socket cap expected 429, got ${status}`);
  sockets.forEach(close);
}

console.log('PASS live-safe abuse checks: single-helper + bad-signal + size bound + socket cap');
setTimeout(() => process.exit(0), 100);
