import test from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.js';

function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.testQueue = [];
    ws.testWaiter = null;
    ws.on('message', data => {
      const message = JSON.parse(data.toString());
      const waiter = ws.testWaiter;
      if (waiter) {
        ws.testWaiter = null;
        waiter.resolve(message);
      } else {
        ws.testQueue.push(message);
      }
    });
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

function next(ws) {
  if (ws.testQueue.length) return Promise.resolve(ws.testQueue.shift());
  return new Promise((resolve, reject) => {
    ws.testWaiter = { resolve, reject };
  });
}

async function closeServer(server, sockets) {
  for (const ws of sockets) {
    if (ws.readyState !== WebSocket.CLOSED) ws.terminate();
  }
  await new Promise(resolve => server.close(resolve));
}

async function fixture() {
  const server = createServer({ port: 0 });
  await new Promise(resolve => server.once('listening', resolve));
  const { port } = server.address();
  return { server, url: `ws://127.0.0.1:${port}/ws` };
}

test('register, approve and relay signaling', async () => {
  const { server, url } = await fixture();
  const host = await open(url);
  const viewer = await open(url);

  assert.equal((await next(host)).type, 'ice_config');
  assert.equal((await next(viewer)).type, 'ice_config');

  host.send(JSON.stringify({ type: 'register', code: '123456', hostKey: 'host-session-key-123456' }));
  assert.equal((await next(host)).type, 'registered');

  viewer.send(JSON.stringify({ type: 'join', code: '123456', requestId: 'request-0001' }));
  assert.equal((await next(host)).type, 'incoming_request');
  assert.equal((await next(viewer)).type, 'request_sent');

  host.send(JSON.stringify({ type: 'approve', requestId: 'request-0001' }));
  assert.equal((await next(viewer)).type, 'approved');

  viewer.send(JSON.stringify({ type: 'signal', requestId: 'request-0001', payload: { kind: 'offer' } }));
  const relayed = await next(host);
  assert.equal(relayed.type, 'signal');
  assert.equal(relayed.payload.kind, 'offer');

  await closeServer(server, [host, viewer]);
});

test('re-registering a host invalidates its previous temporary code', async () => {
  const { server, url } = await fixture();
  const host = await open(url);
  const viewer = await open(url);
  await next(host);
  await next(viewer);

  host.send(JSON.stringify({ type: 'register', code: '111111', hostKey: 'host-session-key-111111' }));
  assert.equal((await next(host)).code, '111111');
  host.send(JSON.stringify({ type: 'register', code: '222222', hostKey: 'host-session-key-111111' }));
  assert.equal((await next(host)).code, '222222');

  viewer.send(JSON.stringify({ type: 'join', code: '111111', requestId: 'request-old1' }));
  assert.equal((await next(viewer)).type, 'not_found');

  viewer.send(JSON.stringify({ type: 'join', code: '222222', requestId: 'request-new1' }));
  assert.equal((await next(host)).requestId, 'request-new1');
  assert.equal((await next(viewer)).type, 'request_sent');

  await closeServer(server, [host, viewer]);
});

test('a request id cannot be taken over by another viewer', async () => {
  const { server, url } = await fixture();
  const host = await open(url);
  const first = await open(url);
  const second = await open(url);
  await next(host);
  await next(first);
  await next(second);

  host.send(JSON.stringify({ type: 'register', code: '333333', hostKey: 'host-session-key-333333' }));
  await next(host);

  first.send(JSON.stringify({ type: 'join', code: '333333', requestId: 'shared-request' }));
  assert.equal((await next(host)).type, 'incoming_request');
  assert.equal((await next(first)).type, 'request_sent');

  second.send(JSON.stringify({ type: 'join', code: '333333', requestId: 'shared-request' }));
  const denied = await next(second);
  assert.equal(denied.type, 'busy');
  assert.equal(denied.reason, 'request_id_in_use');

  await closeServer(server, [host, first, second]);
});

test('pending approval survives a host reconnect with the same session key', async () => {
  const { server, url } = await fixture();
  const host1 = await open(url);
  const viewer = await open(url);
  await next(host1);
  await next(viewer);

  const hostKey = 'host-session-key-reconnect';
  host1.send(JSON.stringify({ type: 'register', code: '444444', hostKey }));
  assert.equal((await next(host1)).type, 'registered');

  viewer.send(JSON.stringify({ type: 'join', code: '444444', requestId: 'reconnect-request' }));
  assert.equal((await next(host1)).type, 'incoming_request');
  assert.equal((await next(viewer)).type, 'request_sent');

  host1.close();
  await new Promise(resolve => host1.once('close', resolve));

  const host2 = await open(url);
  await next(host2);
  host2.send(JSON.stringify({ type: 'register', code: '444444', hostKey }));
  assert.equal((await next(host2)).type, 'registered');

  host2.send(JSON.stringify({ type: 'approve', requestId: 'reconnect-request' }));
  assert.equal((await next(viewer)).type, 'approved');

  await closeServer(server, [host2, viewer]);
});


test('approved viewer can reconnect with the same viewer key', async () => {
  const { server, url } = await fixture();
  const host = await open(url);
  const viewer1 = await open(url);
  await next(host);
  await next(viewer1);

  const viewerKey = 'viewer-session-key-reconnect';
  host.send(JSON.stringify({ type: 'register', code: '555555', hostKey: 'host-session-key-555555' }));
  await next(host);

  viewer1.send(JSON.stringify({
    type: 'join',
    code: '555555',
    requestId: 'viewer-reconnect-request',
    viewerKey
  }));
  assert.equal((await next(host)).type, 'incoming_request');
  assert.equal((await next(viewer1)).type, 'request_sent');

  host.send(JSON.stringify({ type: 'approve', requestId: 'viewer-reconnect-request' }));
  assert.equal((await next(viewer1)).type, 'approved');

  viewer1.close();
  await new Promise(resolve => viewer1.once('close', resolve));

  const viewer2 = await open(url);
  await next(viewer2);
  viewer2.send(JSON.stringify({
    type: 'join',
    code: '555555',
    requestId: 'viewer-reconnect-request',
    viewerKey
  }));
  assert.equal((await next(viewer2)).type, 'request_sent');
  assert.equal((await next(viewer2)).type, 'approved');

  viewer2.send(JSON.stringify({
    type: 'signal',
    requestId: 'viewer-reconnect-request',
    payload: { kind: 'candidate', candidate: 'reconnected' }
  }));
  const relayed = await next(host);
  assert.equal(relayed.type, 'signal');
  assert.equal(relayed.payload.candidate, 'reconnected');

  await closeServer(server, [host, viewer2]);
});
