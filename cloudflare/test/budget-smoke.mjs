import { WebSocket } from '../../server/node_modules/ws/wrapper.mjs';

const base = process.env.SIMPLELINK_TEST_WS || 'ws://127.0.0.1:8796/ws';

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
    ws.waiters.push(message => { clearTimeout(timer); resolve(message); });
  });
}

async function attempt(index) {
  const code = String(710000 + index);
  const host = await open(code, `203.0.113.${10 + index * 2}`);
  const viewer = await open(code, `203.0.113.${11 + index * 2}`);
  await next(host); await next(viewer);

  const hostKey = `host-budget-${crypto.randomUUID()}`;
  host.send(JSON.stringify({ type: 'register', code, hostKey }));
  await next(host);

  viewer.send(JSON.stringify({
    type: 'join',
    code,
    requestId: `request-budget-${crypto.randomUUID()}`,
    viewerKey: `viewer-budget-${crypto.randomUUID()}`
  }));
  const reply = await next(viewer);
  host.close(); viewer.close();
  return reply;
}

const first = await attempt(1);
const second = await attempt(2);
const third = await attempt(3);

if (first.type !== 'request_sent' || second.type !== 'request_sent') {
  throw new Error('first two admissions should pass');
}
if (third.type !== 'busy' || third.reason !== 'daily_capacity') {
  throw new Error(`third admission should hit daily capacity: ${JSON.stringify(third)}`);
}

console.log('PASS daily admission budget preserves a hard cap for new sessions');
