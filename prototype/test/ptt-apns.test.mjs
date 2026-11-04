import test from 'node:test';
import assert from 'node:assert/strict';
import { connect as connectHttp2, createServer, sensitiveHeaders } from 'node:http2';
import { createServer as createTcpServer } from 'node:net';
import { generateKeyPairSync, verify } from 'node:crypto';
import { once } from 'node:events';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createApnsProvider, loadApnsProviderFromEnvironment, MAX_APNS_KEY_FILE_BYTES } from '../ptt/apns.mjs';
import { runningApp } from './support.mjs';

// Generated ephemeral test key. No Apple account, real key, token, or APNs endpoint is used by tests.
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const identity = { teamId: 'TESTTEAM01', keyId: 'TESTKEY001', bundleId: 'test.pleos.mock', environment: 'sandbox' };
const config = { ...identity, privateKey };
const token = 'ab'.repeat(43);
const payload = { v: 1, roomId: '12'.repeat(12), channelUUID: 'ab012345-6789-4abc-8def-0123456789ab',
  registrationId: '34'.repeat(12), message: { id: '56'.repeat(12), sender: { id: '78'.repeat(12), name: '가상 발신자' },
    sequence: 2, acceptedAt: 1_800_000_000_000, autoPlayUntil: 1_800_000_030_000, durationMs: 1000,
    byteLength: 32044, sampleCount: 16000, sampleRate: 16000, channels: 1, format: 'pcm_s16le' },
  serverTime: 1_800_000_000_000, roomExpiresAt: 1_800_021_600_000 };
async function until(check, label = 'condition') {
  const end = Date.now() + 2000;
  while (!check()) {
    if (Date.now() >= end) assert.fail(`Timed out waiting for ${label}`);
    await delay(2);
  }
}
async function mockHttp2(t, handler) {
  const sessions = new Set(); const requests = []; const connections = []; const clientHeaders = []; const clients = [];
  const server = createServer();
  server.on('session', (session) => { sessions.add(session); session.on('error', () => {}); session.on('close', () => sessions.delete(session)); });
  server.on('stream', (stream, headers) => {
    stream.on('error', () => {});
    let chunks = [];
    stream.on('data', (chunk) => chunks.push(chunk));
    stream.on('end', () => {
      const request = { stream, headers, body: Buffer.concat(chunks) };
      chunks = [];
      requests.push(request);
      handler(request, requests.length - 1);
    });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => {
    for (const client of clients) client.destroy();
    for (const session of sessions) session.destroy();
    await new Promise((fulfill) => server.close(fulfill));
  });
  return { requests, connections, clientHeaders, clients, sessions,
    // Mock transport is explicit. Only this connector redirects the fixed Apple authority to loopback h2c.
    connect(authority, options) {
      connections.push({ authority, options });
      const client = connectHttp2(base); clients.push(client);
      const request = client.request.bind(client);
      client.request = (headers) => { clientHeaders.push(headers); return request(headers); };
      return client;
    },
  };
}
function respond({ stream }, status = 200, value) {
  stream.respond({ ':status': status });
  stream.end(value === undefined ? undefined : JSON.stringify(value));
}
function assertJwt(authorization, issuedAt) {
  assert(authorization.startsWith('bearer '));
  const jwt = authorization.slice(7);
  const parts = jwt.split('.'); assert.equal(parts.length, 3);
  assert(parts.every((part) => /^[A-Za-z0-9_-]+$/.test(part)));
  assert.deepEqual(JSON.parse(Buffer.from(parts[0], 'base64url')), { alg: 'ES256', kid: identity.keyId });
  assert.deepEqual(JSON.parse(Buffer.from(parts[1], 'base64url')), { iss: identity.teamId, iat: issuedAt });
  const signature = Buffer.from(parts[2], 'base64url'); assert.equal(signature.length, 64);
  assert(verify('sha256', Buffer.from(`${parts[0]}.${parts[1]}`),
    { key: publicKey, dsaEncoding: 'ieee-p1363' }, signature));
}

test('mock HTTP/2 contract sends correct path/pushtotalk headers, verifies ES256 JOSE signature, and reuses connection/JWT', async (t) => {
  let now = 1_800_000_000_999;
  const mock = await mockHttp2(t, (request) => respond(request));
  const provider = createApnsProvider(config, { connect: mock.connect, clock: () => now }); t.after(() => provider.close());
  assert.equal(mock.connections.length, 0);
  assert.deepEqual(await provider.send({ token, payload }), { status: 200 });
  const request = mock.requests[0];
  assert.equal(request.headers[':method'], 'POST'); assert.equal(request.headers[':path'], `/3/device/${token}`);
  assert.equal(request.headers['apns-push-type'], 'pushtotalk');
  assert.equal(request.headers['apns-topic'], `${identity.bundleId}.voip-ptt`);
  assert.equal(request.headers['apns-priority'], '10'); assert.equal(request.headers['apns-expiration'], '0');
  assert.match(request.headers['apns-id'], /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  assert.equal(request.headers['content-type'], 'application/json'); assert.equal(Number(request.headers['content-length']), request.body.length);
  assert.deepEqual(JSON.parse(request.body), payload);
  assert(mock.clientHeaders[0][sensitiveHeaders].includes(':path'));
  assert(mock.clientHeaders[0][sensitiveHeaders].includes('authorization'));
  assertJwt(request.headers.authorization, Math.floor(now / 1000));
  now += 20 * 60 * 1000;
  await provider.send({ token, payload });
  assert.equal(mock.requests[1].headers.authorization, request.headers.authorization);
  now += 30 * 60 * 1000;
  await provider.send({ token, payload });
  assert.notEqual(mock.requests[2].headers.authorization, request.headers.authorization);
  assertJwt(mock.requests[2].headers.authorization, Math.floor(now / 1000));
  assert.deepEqual(mock.connections, [{ authority: 'https://api.sandbox.push.apple.com',
    options: { minVersion: 'TLSv1.2', rejectUnauthorized: true } }]);
});

test('real HTTP voice/registration integration uses the APNs provider with only an injected mock HTTP/2 transport', async (t) => {
  const mock = await mockHttp2(t, (request) => respond(request));
  const provider = createApnsProvider(config, { connect: mock.connect });
  const app = await runningApp(t, { pttProvider: provider }); const host = await app.create(); const member = await app.join(host);
  const registered = await app.call('POST', `/api/rooms/${host.roomId}/ptt`, { token: member.token,
    body: { channelUUID: payload.channelUUID, token, expectedRevision: 0 } });
  const sent = await app.upload(host, 1);
  assert.equal(sent.status, 201);
  await until(() => mock.requests.length === 1 && app.pttStats().active === 0);
  const notification = JSON.parse(mock.requests[0].body);
  assert.equal(notification.registrationId, registered.data.registration.id);
  assert.deepEqual(notification.message, sent.data.message);
  assert(!mock.requests[0].body.includes(Buffer.from(host.token)));
  assert(!mock.requests[0].body.includes(Buffer.from(member.token)));
  await app.upload(host, 1);
  assert.equal(mock.requests.length, 1);
  await app.close();
  await until(() => mock.clients.every((client) => client.destroyed), 'provider transport closed');
});

test('mock APNs response parsing keeps status/reason separate and bounds malformed/large response bodies', async (t) => {
  const mock = await mockHttp2(t, (request, index) => {
    if (index === 0) respond(request, 400, { reason: 'BadDeviceToken' });
    else if (index === 1) respond(request, 410, { reason: 'Unregistered', timestamp: 1_800_000_000_000 });
    else if (index === 2) { request.stream.respond({ ':status': 403 }); request.stream.end('{broken'); }
    else if (index === 3) respond(request, 403, { reason: token });
    else { request.stream.respond({ ':status': 500 }); request.stream.end('x'.repeat(4097)); }
  });
  const provider = createApnsProvider(config, { connect: mock.connect }); t.after(() => provider.close());
  assert.deepEqual(await provider.send({ token, payload }), { status: 400, reason: 'BadDeviceToken' });
  assert.deepEqual(await provider.send({ token, payload }), { status: 410, reason: 'Unregistered' });
  await assert.rejects(provider.send({ token, payload }), { code: 'invalid_response' });
  await assert.rejects(provider.send({ token, payload }), (error) => error.code === 'invalid_response' && !error.message.includes(token));
  await assert.rejects(provider.send({ token, payload }), { code: 'response_too_large' });
});

test('request deadline, AbortSignal and provider close terminate real mock HTTP/2 streams', async (t) => {
  const mock = await mockHttp2(t, () => {});
  const provider = createApnsProvider(config, { connect: mock.connect, requestTimeoutMs: 200 });
  t.after(() => provider.close());
  const timedOut = provider.send({ token, payload, timeoutMs: 30 });
  await assert.rejects(timedOut, { code: 'timeout' });
  await until(() => mock.requests[0]?.stream.closed, 'timeout stream cancelled');
  const controller = new AbortController();
  const aborted = provider.send({ token, payload, signal: controller.signal });
  const abortedResult = assert.rejects(aborted, { code: 'cancelled' });
  await until(() => mock.requests.length === 2); controller.abort(); await abortedResult;
  await until(() => mock.requests[1].stream.closed, 'abort stream cancelled');
  const pending = provider.send({ token, payload });
  const closedResult = assert.rejects(pending, { code: 'closed' });
  await until(() => mock.requests.length === 3); provider.close(); await closedResult;
  await until(() => mock.sessions.size === 0, 'HTTP/2 sessions released');
  await assert.rejects(provider.send({ token, payload }), { code: 'closed' });
});

test('GOAWAY retires a mock HTTP/2 session and a later request gets a fresh connection', async (t) => {
  const mock = await mockHttp2(t, (request, index) => {
    respond(request);
    if (index === 0) request.stream.session.goaway();
  });
  const provider = createApnsProvider(config, { connect: mock.connect }); t.after(() => provider.close());
  assert.deepEqual(await provider.send({ token, payload }), { status: 200 });
  await until(() => mock.clients[0].closed, 'GOAWAY session retired');
  assert.deepEqual(await provider.send({ token, payload }), { status: 200 });
  assert.equal(mock.connections.length, 2);
});

test('failed transport rejects with sanitized error and releases the session', async (t) => {
  const mock = await mockHttp2(t, (request) => request.stream.session.destroy());
  const provider = createApnsProvider(config, { connect: mock.connect }); t.after(() => provider.close());
  await assert.rejects(provider.send({ token, payload }), (error) =>
    /^transport_/.test(error.code) && !error.message.includes(token) && !error.message.includes(identity.teamId));
  await until(() => mock.sessions.size === 0);
});

test('silent mock TCP peer cannot hold a request past its bounded deadline and close releases the socket', async (t) => {
  const sockets = new Set();
  const tcp = createTcpServer((socket) => { sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket)); });
  tcp.listen(0, '127.0.0.1'); await once(tcp, 'listening');
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise((fulfill) => tcp.close(fulfill)); });
  const clients = [];
  const provider = createApnsProvider(config, { requestTimeoutMs: 30,
    connect: () => { const client = connectHttp2(`http://127.0.0.1:${tcp.address().port}`); clients.push(client); return client; } });
  t.after(() => provider.close());
  await assert.rejects(provider.send({ token, payload }), { code: 'timeout' });
  provider.close(); await until(() => clients.every((client) => client.destroyed));
});

test('invalid request/configuration never invokes the connector; only Apple environment enums are allowed', async () => {
  let connections = 0;
  const connect = () => { connections += 1; throw new Error('no external transport'); };
  const provider = createApnsProvider(config, { connect });
  for (const invalid of ['', 'a', 'gg', 'ab'.repeat(513), ` ${token}`, token.toUpperCase()]) {
    await assert.rejects(provider.send({ token: invalid, payload }), { code: 'invalid_request' });
  }
  await assert.rejects(provider.send({ token, payload: { huge: 'x'.repeat(4096) } }), { code: 'payload_too_large' });
  const circular = {}; circular.self = circular;
  await assert.rejects(provider.send({ token, payload: circular }), { code: 'invalid_payload' });
  const aborted = new AbortController(); aborted.abort();
  await assert.rejects(provider.send({ token, payload, signal: aborted.signal }), { code: 'cancelled' });
  for (const change of [{ environment: 'https://attacker.invalid' }, { environment: 'development' },
    { teamId: 'short' }, { keyId: 'unsafe\nheader' }, { bundleId: 'test.*' }, { bundleId: 'test..app' },
    { privateKey: publicKey }]) assert.throws(() => createApnsProvider({ ...config, ...change }, { connect }), TypeError);
  assert.throws(() => createApnsProvider(config, { requestTimeoutMs: 10_001 }), TypeError);
  assert.equal(connections, 0); provider.close();
});

test('CLI environment loader is disabled only when absent, fails closed for partial/unsupported config, and lazily reads an explicit bounded P-256 file', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'pleos-ptt-key-test-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const keyFile = join(directory, 'ephemeral-test.p8');
  await writeFile(keyFile, privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
  const env = { PLEOS_APNS_TEAM_ID: identity.teamId, PLEOS_APNS_KEY_ID: identity.keyId,
    PLEOS_APNS_BUNDLE_ID: identity.bundleId, PLEOS_APNS_KEY_FILE: keyFile, PLEOS_APNS_ENVIRONMENT: 'sandbox' };
  let connections = 0;
  const options = { connect: () => { connections += 1; throw new Error('external APNs forbidden'); } };
  assert.equal(await loadApnsProviderFromEnvironment({}, options), null);
  for (const invalid of [{ PLEOS_APNS_TEAM_ID: identity.teamId }, { PLEOS_APNS_KEY_FILE: '' },
    { ...env, PLEOS_APNS_ENVIRONMENT: 'https://attacker.invalid' }, { ...env, PLEOS_APNS_ENDPOINT: 'https://attacker.invalid' },
    { ...env, PLEOS_APNS_KEY: 'never accept raw secret' }, { ...env, PLEOS_APNS_KEY_ID: '' },
    { ...env, PLEOS_APNS_KEY_FILE: join(directory, 'missing') }]) {
    await assert.rejects(loadApnsProviderFromEnvironment(invalid, options), (error) =>
      error instanceof TypeError && !error.message.includes(keyFile) && !error.message.includes('never accept raw secret'));
  }
  for (const contents of [Buffer.alloc(MAX_APNS_KEY_FILE_BYTES + 1), Buffer.from('not a private test key'),
    generateKeyPairSync('ec', { namedCurve: 'secp384r1' }).privateKey.export({ format: 'pem', type: 'pkcs8' }),
    publicKey.export({ format: 'pem', type: 'spki' })]) {
    const invalidFile = join(directory, 'invalid.p8'); await writeFile(invalidFile, contents);
    await assert.rejects(loadApnsProviderFromEnvironment({ ...env, PLEOS_APNS_KEY_FILE: invalidFile }, options), TypeError);
  }
  const folder = join(directory, 'folder'); await mkdir(folder);
  await assert.rejects(loadApnsProviderFromEnvironment({ ...env, PLEOS_APNS_KEY_FILE: folder }, options), TypeError);
  const provider = await loadApnsProviderFromEnvironment(env, options);
  assert.equal(connections, 0); provider.close();
  const production = createApnsProvider({ ...config, environment: 'production' }, {
    connect(authority, tls) {
      assert.equal(authority, 'https://api.push.apple.com'); assert.equal(tls.rejectUnauthorized, true);
      connections += 1; throw new Error('intentional mock connector rejection');
    },
  });
  await assert.rejects(production.send({ token, payload }), { code: 'transport_error' });
  assert.equal(connections, 1); production.close();
});
