import test from 'node:test';
import assert from 'node:assert/strict';
import http2 from 'node:http2';
import { syncBuiltinESMExports } from 'node:module';
import { request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { createPrototypeServer } from '../server.mjs';
import { MAX_PTT_TOKEN_BYTES, PTT_DEFAULTS } from '../ptt/adapter.mjs';
import { fictionalWav, runningApp } from './support.mjs';

const channelA = 'ab012345-6789-4abc-8def-0123456789ab';
const channelB = 'cd012345-6789-4abc-8def-0123456789cd';
const path = (member) => `/api/rooms/${member.roomId}/ptt`;
const body = (token = 'ab'.repeat(43), expectedRevision = 0, channelUUID = channelA) => ({ channelUUID, token, expectedRevision });
const removeBody = (expectedRevision = 0, channelUUID = channelA) => ({ channelUUID, expectedRevision });
const register = (app, member, token, revision = 0, channel = channelA) =>
  app.call('POST', path(member), { token: member.token, body: body(token, revision, channel) });
const get = (app, member) => app.call('GET', path(member), { token: member.token });
const unregister = (app, member, revision, channel = channelA) =>
  app.call('DELETE', path(member), { token: member.token, body: removeBody(revision, channel) });
async function until(check, message = 'condition', timeoutMs = 2000) {
  const untilAt = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() >= untilAt) assert.fail(`Timed out waiting for ${message}`);
    await delay(2);
  }
}
function mockProvider({ immediate = false } = {}) {
  const calls = [];
  let closed = 0;
  return { calls, get closed() { return closed; },
    // Explicitly a mock: a 200 below says nothing about APNs or device playback.
    send(request) {
      if (immediate) { calls.push(request); return Promise.resolve({ status: 200 }); }
      return new Promise((resolve, reject) => calls.push({ ...request, resolve, reject }));
    },
    close() { closed += 1; },
  };
}
async function pendingJson(app, member, method, value) {
  const accepted = once(app.server, 'request');
  const bytes = Buffer.from(JSON.stringify(value));
  let request;
  const result = new Promise((fulfill, fail) => {
    request = httpRequest(`${app.base}${path(member)}`, { method, headers: {
      Authorization: `Bearer ${member.token}`, 'Content-Type': 'application/json', 'Transfer-Encoding': 'chunked',
    } }, (response) => {
      let text = '';
      response.on('data', (chunk) => { text += chunk; });
      response.on('end', () => fulfill({ status: response.statusCode, data: JSON.parse(text) }));
    });
    request.on('error', fail);
    request.write(bytes.subarray(0, 2));
  });
  await accepted;
  return { result, complete: () => request.end(bytes.subarray(2)) };
}

test('default factory ignores APNs environment and performs zero external connections or token retention', async (t) => {
  const originalConnect = http2.connect;
  const previous = process.env.PLEOS_APNS_TEAM_ID;
  let external = 0;
  http2.connect = () => { external += 1; throw new Error('external APNs forbidden in test'); };
  syncBuiltinESMExports();
  process.env.PLEOS_APNS_TEAM_ID = 'invalid explicit CLI configuration';
  t.after(() => {
    http2.connect = originalConnect; syncBuiltinESMExports();
    if (previous === undefined) delete process.env.PLEOS_APNS_TEAM_ID;
    else process.env.PLEOS_APNS_TEAM_ID = previous;
  });
  const now = 1_800_000_000_000;
  const app = await runningApp(t, { clock: () => now });
  const host = await app.create(); const member = await app.join(host);
  const initial = { configured: false, status: 'not-configured', revision: 0, registration: null, serverTime: now };
  assert.deepEqual((await get(app, member)).data, initial);
  const token = 'fe'.repeat(89);
  const post = await register(app, member, token);
  assert.equal(post.status, 200); assert.deepEqual(post.data, { ...initial, revision: 1 });
  assert.deepEqual((await get(app, member)).data, post.data);
  assert.deepEqual((await unregister(app, member, 1)).data, { ...initial, revision: 2 });
  const stale = await register(app, member, token, 0);
  assert.equal(stale.status, 409); assert.equal(stale.data.code, 'ptt_revision_conflict');
  assert.equal(stale.data.revision, 2); assert.equal(stale.data.registration, null);
  await app.upload(host, 1);
  assert.equal(external, 0);
  assert.deepEqual(app.pttStats(), { configured: false, registrations: 0, queued: 0, active: 0 });
  assert(!JSON.stringify([post.data, stale.data, (await app.call('GET', `/api/rooms/${host.roomId}`, { token: member.token })).data, app.stats()]).includes(token));
});

test('PTT routes require this room member bearer, reject invite/query credentials, and isolate other routes', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const outsider = await app.create('다른 공유방');
  for (const method of ['GET', 'POST', 'DELETE']) {
    const value = method === 'GET' ? undefined : method === 'POST' ? body() : removeBody();
    for (const [token, status] of [[undefined, 401], [host.inviteSecret, 403], [outsider.token, 403]]) {
      assert.equal((await app.call(method, path(member), { token, body: value })).status, status);
    }
  }
  assert.equal((await app.call('GET', `${path(member)}?token=${member.token}`)).status, 400);
  assert.equal((await app.call('GET', `${path(member)}/extra`, { token: member.token })).status, 404);
  assert.equal((await app.call('GET', `${path(member)}/`, { token: member.token })).status, 404);
  assert.equal((await app.call('PUT', path(member), { token: member.token })).status, 405);
  await register(app, member);
  const state = (await app.call('GET', `/api/rooms/${host.roomId}`, { token: host.token })).data;
  assert.equal(state.revision, 0); assert.equal(state.sequence, 1); assert.deepEqual(state.voiceMessages, []);
  assert.equal((await get(app, host)).data.revision, 0);
});

test('strict schema permits variable-length bounded hex and canonical UUID only, without reflecting tokens', async (t) => {
  const provider = mockProvider({ immediate: true }); const app = await runningApp(t, { pttProvider: provider });
  const host = await app.create(); const valid = body();
  const invalid = [null, [], {}, { ...valid, extra: true }, { ...valid, channelUUID: null },
    { ...valid, channelUUID: channelA.replaceAll('-', '') }, { ...valid, channelUUID: ` ${channelA}` },
    { ...valid, expectedRevision: -1 }, { ...valid, expectedRevision: 0.5 },
    { ...valid, expectedRevision: '0' }, { ...valid, expectedRevision: Number.MAX_SAFE_INTEGER + 1 },
    { ...valid, token: '' }, { ...valid, token: 'a' }, { ...valid, token: 'abc' },
    { ...valid, token: 'gg' }, { ...valid, token: '<ab>' }, { ...valid, token: 'ab cd' },
    { ...valid, token: `ab\n` }, { ...valid, token: null }, { ...valid, token: 'ab'.repeat(MAX_PTT_TOKEN_BYTES + 1) }];
  for (const value of invalid) {
    const result = await app.call('POST', path(host), { token: host.token, body: value });
    assert.equal(result.status, 400); assert.equal(result.data.code, 'invalid_ptt_registration');
    assert(!JSON.stringify(result.data).includes(valid.token));
  }
  for (const value of [null, [], {}, { ...removeBody(), token: 'ab' }, { ...removeBody(), expectedRevision: -1 }]) {
    assert.equal((await app.call('DELETE', path(host), { token: host.token, body: value })).status, 400);
  }
  assert.equal((await app.call('POST', path(host), { token: host.token, body: Buffer.from('{'), headers: { 'Content-Type': 'application/json' } })).status, 400);
  assert.equal((await app.call('POST', path(host), { token: host.token, body: Buffer.from('{}'), headers: { 'Content-Type': 'text/plain' } })).status, 415);
  assert.equal((await app.call('POST', path(host), { token: host.token, body: Buffer.alloc(4097), headers: { 'Content-Type': 'application/json' } })).status, 413);
  assert.equal((await get(app, host)).data.revision, 0);
  const first = await register(app, host, 'AB', 0, channelA.toUpperCase());
  assert.equal(first.status, 200); assert.equal(first.data.registration.channelUUID, channelA);
  const maximumToken = 'CD'.repeat(MAX_PTT_TOKEN_BYTES);
  const maximum = await register(app, host, maximumToken, 1, channelB);
  assert.equal(maximum.status, 200); assert.equal(maximum.data.revision, 2);
  assert.match(maximum.data.registration.id, /^[a-f0-9]{24}$/);
  assert(!JSON.stringify(maximum.data).includes(maximumToken));
  assert.equal(app.pttStats().registrations, 1); assert.equal(provider.calls.length, 0);
});

test('member CAS prevents old POST/DELETE overwrites and protects an active different channel', async (t) => {
  const app = await runningApp(t, { pttProvider: mockProvider() }); const host = await app.create(); const member = await app.join(host);
  const first = await register(app, host, 'a1');
  assert.equal(first.data.revision, 1);
  assert.equal((await register(app, host, 'b2', 0, channelB)).status, 409);
  const second = await register(app, host, 'b2', 1, channelB);
  assert.equal(second.data.revision, 2); assert.notEqual(second.data.registration.id, first.data.registration.id);
  assert.equal((await unregister(app, host, 1)).data.code, 'ptt_revision_conflict');
  const wrongChannel = await unregister(app, host, 2);
  assert.equal(wrongChannel.status, 409); assert.equal(wrongChannel.data.code, 'ptt_channel_conflict');
  assert.equal(wrongChannel.data.registration.id, second.data.registration.id);
  assert.equal((await unregister(app, host, 2, channelB)).data.revision, 3);
  assert.equal((await unregister(app, host, 3)).data.revision, 4);
  assert.equal((await register(app, host, 'c3', 3)).status, 409);
  assert.equal((await register(app, host, 'c3', 4)).data.revision, 5);
  assert.equal((await get(app, member)).data.revision, 0);
});

test('one normalized token cannot be shared by members or rooms and is released by DELETE', async (t) => {
  const app = await runningApp(t, { pttProvider: mockProvider() }); const first = await app.create(); const second = await app.create();
  await register(app, first, 'AB'.repeat(50));
  const reused = await register(app, second, 'ab'.repeat(50));
  assert.equal(reused.status, 409); assert.equal(reused.data.code, 'ptt_token_in_use'); assert.equal(reused.data.revision, 0);
  await unregister(app, first, 1);
  assert.equal((await register(app, second, 'AB'.repeat(50))).status, 200);
  assert.equal(app.pttStats().registrations, 1);
});

test('a completed empty DELETE fences a POST whose authenticated JSON body is still in flight', async (t) => {
  const app = await runningApp(t, { pttProvider: mockProvider() }); const host = await app.create();
  const pending = await pendingJson(app, host, 'POST', body('a1'));
  assert.equal((await unregister(app, host, 0)).data.revision, 1);
  pending.complete();
  const old = await pending.result;
  assert.equal(old.status, 409); assert.equal(old.data.revision, 1); assert.equal(old.data.registration, null);
  assert.equal(app.pttStats().registrations, 0);
});

test('POST and DELETE reauthenticate after body read against leave, close and expiry', async (t) => {
  for (const method of ['POST', 'DELETE']) for (const action of ['leave', 'close', 'expiry']) {
    let now = 1000;
    const app = await runningApp(t, { clock: () => now, ttlMs: 100_000, pttProvider: mockProvider() });
    const host = await app.create(); const member = await app.join(host);
    const pending = await pendingJson(app, member, method, method === 'POST' ? body('a1') : removeBody());
    if (action === 'leave') await app.call('DELETE', `/api/rooms/${host.roomId}/members/me`, { token: member.token });
    else if (action === 'close') await app.call('DELETE', `/api/rooms/${host.roomId}`, { token: host.token });
    else now += 100_001;
    pending.complete();
    assert.equal((await pending.result).status, action === 'leave' ? 403 : 404, `${method} ${action}`);
    assert.equal(app.pttStats().registrations, 0);
  }
});

test('only newly accepted WAVs notify other active room registrations with public metadata', async (t) => {
  const provider = mockProvider({ immediate: true }); let now = 1_800_000_000_000;
  const app = await runningApp(t, { pttProvider: provider, clock: () => now });
  const host = await app.create(); const first = await app.join(host, '첫 수신자'); const second = await app.join(host, '둘째 수신자');
  const otherRoom = await app.create('다른 방');
  const registrations = await Promise.all([register(app, host, 'a1'), register(app, first, 'b2'),
    register(app, second, 'c3', 0, channelB), register(app, otherRoom, 'd4')]);
  const sent = await app.upload(host, 1, fictionalWav(32_000));
  assert.equal(sent.status, 201); assert.equal(provider.calls.length, 2);
  assert.deepEqual(provider.calls.map((call) => call.token).sort(), ['b2', 'c3']);
  for (const call of provider.calls) {
    const receiver = call.token === 'b2' ? 1 : 2;
    assert.deepEqual(call.payload, { v: 1, roomId: host.roomId,
      channelUUID: registrations[receiver].data.registration.channelUUID,
      registrationId: registrations[receiver].data.registration.id, message: sent.data.message,
      serverTime: now, roomExpiresAt: host.state.room.expiresAt });
    assert(!JSON.stringify(call.payload).includes(host.token));
    assert(!JSON.stringify(call.payload).includes('downloadURL')); assert(!Object.hasOwn(call.payload, 'audio'));
  }
  now += 100;
  assert.equal((await app.upload(host, 1, fictionalWav(32_000))).status, 200);
  assert.equal((await app.upload(host, 1, fictionalWav())).status, 409);
  assert.equal((await app.upload(host, 2, Buffer.from('invalid WAV'))).status, 422);
  assert.equal(provider.calls.length, 2);
  await app.upload(first, 1);
  assert.deepEqual(provider.calls.slice(2).map((call) => call.token).sort(), ['a1', 'c3']);
  await until(() => app.pttStats().active === 0);
});

test('bounded queue drops overflow, times out active mock deliveries and never retries a voice', async (t) => {
  const provider = mockProvider(); const app = await runningApp(t, { pttProvider: provider,
    pttLimits: { maxConcurrent: 1, maxQueued: 1, timeoutMs: 150 } });
  const host = await app.create(); const member = await app.join(host); await register(app, member, 'a1');
  const sent = await Promise.all(Array.from({ length: 5 }, (_, index) => app.upload(host, index)));
  assert(sent.every((result) => result.status === 201));
  assert.equal(provider.calls.length, 1); assert.equal(app.pttStats().active, 1); assert.equal(app.pttStats().queued, 1);
  await until(() => provider.calls.length === 2, 'second queued mock request');
  assert(provider.calls[0].signal.aborted);
  await until(() => app.pttStats().active === 0, 'second mock timeout');
  assert(provider.calls[1].signal.aborted); assert.equal(app.pttStats().queued, 0);
  assert.equal(provider.calls.length, 2);
});

test('queued work rechecks freshness and queue age immediately before provider dispatch', async (t) => {
  for (const elapsed of [10_001, 30_001]) {
    let now = 100_000; const provider = mockProvider();
    const app = await runningApp(t, { pttProvider: provider, clock: () => now, pttLimits: { maxConcurrent: 1 } });
    const host = await app.create(); const member = await app.join(host); await register(app, member, 'a1');
    await app.upload(host, 1); await app.upload(host, 2);
    assert.equal(app.pttStats().queued, 1);
    now += elapsed; provider.calls[0].resolve({ status: 200 });
    await until(() => app.pttStats().active === 0 && app.pttStats().queued === 0, 'stale queue discarded');
    assert.equal(provider.calls.length, 1);
  }
});

test('replacement cancels active/queued work; late old invalid-token responses cannot delete the new registration', async (t) => {
  for (const invalid of [{ status: 410, reason: 'Unregistered' }, { status: 400, reason: 'BadDeviceToken' }]) {
    const provider = mockProvider(); const app = await runningApp(t, { pttProvider: provider, pttLimits: { maxConcurrent: 1 } });
    const host = await app.create(); const member = await app.join(host);
    const old = await register(app, member, 'a1');
    await app.upload(host, 1); await app.upload(host, 2);
    assert.equal(app.pttStats().queued, 1);
    const replacement = await register(app, member, 'b2', 1, channelB);
    assert.equal(replacement.status, 200); assert.notEqual(replacement.data.registration.id, old.data.registration.id);
    assert(provider.calls[0].signal.aborted); assert.equal(app.pttStats().active, 0); assert.equal(app.pttStats().queued, 0);
    // This intentionally uncooperative mock resolves after its AbortSignal was cancelled.
    provider.calls[0].resolve(invalid);
    await delay(0);
    const state = (await get(app, member)).data;
    assert.equal(state.revision, 2); assert.equal(state.registration.id, replacement.data.registration.id);
    await app.upload(host, 3);
    assert.equal(provider.calls.length, 2); assert.equal(provider.calls[1].token, 'b2');
    provider.calls[1].resolve(invalid);
    await until(() => app.pttStats().registrations === 0, 'current invalid token removal');
    const removed = (await get(app, member)).data;
    assert.equal(removed.revision, 3); assert.equal(removed.registration, null);
  }
});

test('explicit unregister and target/sender leave cancel pending work without replaying historical voice', async (t) => {
  for (const action of ['unregister', 'target-leave', 'sender-leave']) {
    const provider = mockProvider(); const app = await runningApp(t, { pttProvider: provider, pttLimits: { maxConcurrent: 1 } });
    const host = await app.create(); const sender = await app.join(host); const target = await app.join(host);
    await register(app, target, 'a1'); await app.upload(sender, 1); await app.upload(sender, 2);
    assert.equal(app.pttStats().queued, 1);
    if (action === 'unregister') await unregister(app, target, 1);
    else await app.call('DELETE', `/api/rooms/${host.roomId}/members/me`, { token: action === 'target-leave' ? target.token : sender.token });
    assert(provider.calls[0].signal.aborted); assert.equal(app.pttStats().active, 0); assert.equal(app.pttStats().queued, 0);
    provider.calls[0].resolve({ status: 200 }); await delay(0); assert.equal(provider.calls.length, 1);
    if (action === 'unregister') {
      await register(app, target, 'b2', 2);
      assert.equal(provider.calls.length, 1);
    }
  }
});

test('room close, room expiry and server close clear registrations, queued work and active cancellation', async (t) => {
  for (const action of ['close', 'expiry', 'server']) {
    let now = 100_000; const provider = mockProvider();
    const app = await runningApp(t, { pttProvider: provider, clock: () => now, ttlMs: 100_000,
      pttLimits: { maxConcurrent: 1 } });
    const host = await app.create(); const first = await app.join(host); const second = await app.join(host);
    await register(app, first, 'a1'); await register(app, second, 'b2'); await app.upload(host, 1);
    assert.equal(app.pttStats().active, 1); assert.equal(app.pttStats().queued, 1);
    if (action === 'close') await app.call('DELETE', `/api/rooms/${host.roomId}`, { token: host.token });
    else if (action === 'expiry') { now += 100_001; app.cleanupExpired(); }
    else await app.close();
    assert.deepEqual(app.pttStats(), { configured: true, registrations: 0, queued: 0, active: 0 });
    assert(provider.calls[0].signal.aborted);
    assert.deepEqual(app.stats(), { rooms: 0, members: 0, messages: 0, streams: 0, audioBytes: 0 });
    provider.calls[0].resolve({ status: 410 }); await delay(0); assert.equal(provider.calls.length, 1);
    await app.close(); assert.equal(provider.closed, 1);
  }
});

test('one-hour default lease survives thirty minutes of silence and expires within room lifetime', async (t) => {
  let now = 1_800_000_000_000; const provider = mockProvider({ immediate: true });
  const app = await runningApp(t, { pttProvider: provider, clock: () => now });
  const host = await app.create(); const member = await app.join(host);
  const registered = await register(app, member, 'a1');
  assert.equal(PTT_DEFAULTS.leaseMs, 60 * 60 * 1000);
  assert.equal(registered.data.registration.expiresAt, now + 60 * 60 * 1000);
  now += 30 * 60 * 1000 + 1; app.cleanupExpired();
  assert.equal((await get(app, member)).data.registration.id, registered.data.registration.id);
  await app.upload(host, 1); assert.equal(provider.calls.length, 1);
  now = registered.data.registration.expiresAt; app.cleanupExpired();
  const expired = (await get(app, member)).data;
  assert.equal(expired.revision, 2); assert.equal(expired.registration, null);
  await app.upload(host, 2); assert.equal(provider.calls.length, 1);
  const shorter = await runningApp(t, { pttProvider: mockProvider(), clock: () => now, ttlMs: 2_000_000 });
  const shortHost = await shorter.create(); const shortRegistration = await register(shorter, shortHost, 'b2');
  assert.equal(shortRegistration.data.registration.expiresAt, shortHost.state.room.expiresAt);
});

test('transient provider failures keep the registration, release capacity and leave upload acceptance intact', async (t) => {
  const provider = mockProvider(); const app = await runningApp(t, { pttProvider: provider, pttLimits: { maxConcurrent: 1 } });
  const host = await app.create(); const member = await app.join(host); const registration = await register(app, member, 'a1');
  assert.equal((await app.upload(host, 1)).status, 201);
  provider.calls[0].reject(new Error('mock transport failure'));
  await until(() => app.pttStats().active === 0);
  assert.equal((await get(app, member)).data.registration.id, registration.data.registration.id);
  assert.equal((await app.upload(host, 2)).status, 201);
  provider.calls[1].resolve({ status: 503, reason: 'Shutdown' });
  await until(() => app.pttStats().active === 0);
  assert.equal((await get(app, member)).data.revision, 1); assert.equal(provider.calls.length, 2);
});

test('invalid providers and unsafe PTT resource bounds fail before an HTTP server starts', () => {
  assert.throws(() => createPrototypeServer({ pttProvider: {} }), TypeError);
  assert.throws(() => createPrototypeServer({ pttProvider: { send() {} } }), TypeError);
  for (const pttLimits of [{ maxQueued: 0 }, { maxConcurrent: 17 }, { maxQueued: 257 }, { timeoutMs: 10_001 },
    { maxQueueAgeMs: 30_001 }, { leaseMs: 3_600_001 }, { leaseMs: Infinity }, { unexpected: 1 }]) {
    assert.throws(() => createPrototypeServer({ pttLimits }), TypeError);
  }
});
