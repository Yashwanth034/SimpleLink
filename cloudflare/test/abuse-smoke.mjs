import { WebSocket } from '../../server/node_modules/ws/wrapper.mjs';

const base = process.env.SIMPLELINK_TEST_WS || 'ws://127.0.0.1:8795/ws';

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

function close(ws) {
  if (!ws) return;
  try { ws.close(); } catch {}
}

function code() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function key(prefix) {
  return `${prefix}-${crypto.randomUUID()}`;
}

async function expectRejectedOpen(sessionCode, ip) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}?code=${sessionCode}`, {
      headers: { 'CF-Connecting-IP': ip }
    });
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

// 1) One sharing code accepts only one new helper.
{
  const c = code();
  const host = await open(c, '198.51.100.10');
  const first = await open(c, '198.51.100.11');
  const second = await open(c, '198.51.100.12');
  await next(host); await next(first); await next(second);

  const hostKey = key('host');
  host.send(JSON.stringify({ type: 'register', code: c, hostKey }));
  if ((await next(host)).type !== 'registered') throw new Error('host register failed');

  first.send(JSON.stringify({
    type: 'join',
    code: c,
    requestId: key('request'),
    viewerKey: key('viewer')
  }));
  if ((await next(host)).type !== 'incoming_request') throw new Error('first helper not delivered');
  if ((await next(first)).type !== 'request_sent') throw new Error('first helper not accepted');

  second.send(JSON.stringify({
    type: 'join',
    code: c,
    requestId: key('request'),
    viewerKey: key('viewer')
  }));
  const blocked = await next(second);
  if (blocked.type !== 'busy' || blocked.reason !== 'session_busy') {
    throw new Error(`second helper not blocked: ${JSON.stringify(blocked)}`);
  }

  close(host); close(first); close(second);
}

// 2) Wrong-code guesses consume the join limiter.
{
  const c = code();
  const ws = await open(c, '198.51.100.20');
  await next(ws);

  for (let i = 0; i < 12; i += 1) {
    ws.send(JSON.stringify({
      type: 'join',
      code: c,
      requestId: `wrong-${i}-${crypto.randomUUID()}`,
      viewerKey: key('viewer')
    }));
    const reply = await next(ws);
    if (reply.type !== 'not_found') {
      throw new Error(`guess ${i + 1} should be not_found: ${JSON.stringify(reply)}`);
    }
  }

  ws.send(JSON.stringify({
    type: 'join',
    code: c,
    requestId: `wrong-limit-${crypto.randomUUID()}`,
    viewerKey: key('viewer')
  }));
  const limited = await next(ws);
  if (limited.type !== 'busy' || limited.reason !== 'rate_limited') {
    throw new Error(`wrong-code limiter failed: ${JSON.stringify(limited)}`);
  }
  close(ws);
}

// 3) Bad signaling is rejected and oversized messages close the socket.
{
  const c = code();
  const ws = await open(c, '198.51.100.30');
  await next(ws);
  ws.send(JSON.stringify({
    type: 'signal',
    code: c,
    requestId: key('request'),
    payload: { kind: 'not-a-webrtc-kind', blob: 'x' }
  }));
  const reply = await next(ws);
  if (reply.type !== 'error' || reply.reason !== 'bad_signal') {
    throw new Error(`bad signal validation failed: ${JSON.stringify(reply)}`);
  }

  const closed = new Promise(resolve => ws.once('close', (status, reason) => {
    resolve({ status, reason: reason.toString() });
  }));
  ws.send('x'.repeat(100 * 1024));
  const result = await closed;
  if (result.status !== 1009) {
    throw new Error(`oversized message close code was ${result.status}`);
  }
}

// 4) A single code cannot accumulate unlimited idle sockets.
{
  const c = code();
  const sockets = [];
  for (let i = 0; i < 6; i += 1) {
    const ws = await open(c, `198.51.100.${40 + i}`);
    await next(ws);
    sockets.push(ws);
  }
  const status = await expectRejectedOpen(c, '198.51.100.49');
  if (status !== 429) throw new Error(`socket cap expected 429, got ${status}`);
  sockets.forEach(close);
}

// 5) Repeated WebSocket upgrades from one IP are bounded before SignalHub.
{
  const ip = '198.51.100.60';
  for (let i = 0; i < 30; i += 1) {
    const ws = await open(code(), ip);
    await next(ws);
    close(ws);
  }
  const status = await expectRejectedOpen(code(), ip);
  if (status !== 429) throw new Error(`connection limiter expected 429, got ${status}`);
}

// 6) An already-open socket cannot flood signaling messages indefinitely.
{
  const c = code();
  const ws = await open(c, '198.51.100.70');
  await next(ws);
  const closed = new Promise(resolve => ws.once('close', (status, reason) => {
    resolve({ status, reason: reason.toString() });
  }));
  for (let i = 0; i < 161; i += 1) {
    ws.send(JSON.stringify({ type: 'noop', code: c, n: i }));
  }
  const result = await closed;
  if (result.status !== 1008 || result.reason !== 'message_rate_limited') {
    throw new Error(`message flood was not closed correctly: ${JSON.stringify(result)}`);
  }
}

console.log('PASS abuse shield: single-helper + wrong-code throttle + signal bounds + socket cap + connection throttle + message throttle');
