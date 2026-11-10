import test from 'node:test';
import assert from 'node:assert/strict';
import { events, runningApp } from './support.mjs';

const target = { name: '공개 테스트 목적지', latitude: 37.412475, longitude: 127.09534722,
  pleos: { poiId: 'public-test-place', poiSubId: null, address: '공개 테스트 주소', poiProvider: '', displayLatitude: null, displayLongitude: null } };
const id = (n) => n.toString(16).padStart(24, '0');
const path = (who) => `/api/rooms/${who.roomId}`;
const send = (app, who, n = 1, revision = 0, destination = target) => app.call('POST', `${path(who)}/destination-requests`, {
  token: who.token, body: { id: id(n), expectedRequestRevision: revision, destination },
});
const answer = (app, who, value, response = 'accepted') => app.call('POST', `${path(who)}/destination-requests/${value.id}/responses`, {
  token: who.token, body: { revision: value.revision, response },
});
const snapshot = async (app, who) => (await app.call('GET', path(who), { token: who.token })).data;

test('member entry rejects C1 controls consistently with new request person parsing', async (t) => {
  const app = await runningApp(t); const a = await app.create('정상 차량 🚗');
  for (const control of ['\u0080', '\u0085', '\u009f']) {
    const name = `차량${control}이름`;
    assert.equal((await app.call('POST', '/api/rooms', { body: { name } })).status, 400);
    assert.equal((await app.call('POST', `${path(a)}/members`, { token: a.inviteSecret, body: { name } })).status, 400);
  }
  const b = await app.join(a, '정상 참여자 · Cafe');
  const value = (await send(app, a)).data.changeRequest;
  assert.equal(value.sender.name, '정상 차량 🚗');
  assert.equal(value.recipients.find((v) => v.member.id === b.memberId).member.name, '정상 참여자 · Cafe');
});

test('actual HTTP/SSE change request remains separate from legacy destination and each recipient responds independently', async (t) => {
  const app = await runningApp(t); const a = await app.create(); const b = await app.join(a); const c = await app.join(a);
  const feed = await events(t, app, b); await feed.next('snapshot');
  const result = await send(app, a); assert.equal(result.status, 201);
  const value = (await feed.next('destination_request')).data.changeRequest;
  assert.equal(value.recipients.length, 2); assert.equal(value.sender.id, a.memberId);
  assert.equal((await answer(app, b, value)).status, 200);
  let state = await snapshot(app, a);
  assert.equal(state.changeRequest.recipients.find((v) => v.member.id === b.memberId).response, 'accepted');
  assert.equal(state.changeRequest.recipients.find((v) => v.member.id === c.memberId).response, 'pending');
  assert.equal(state.revision, 0); assert.equal(state.destination, null);
  assert.equal((await answer(app, c, value, 'rejected')).status, 200);
  state = await snapshot(app, b);
  assert.equal(state.changeRequest.recipients.find((v) => v.member.id === b.memberId).response, 'accepted');
  assert.equal(state.changeRequest.recipients.find((v) => v.member.id === c.memberId).response, 'rejected');
  assert.equal(state.changeRequest.revision, value.revision);
  assert.equal(feed.history.filter((v) => v.event === 'destination').length, 0);
  assert(!JSON.stringify(state.changeRequest).includes(a.token)); assert(!JSON.stringify(state.changeRequest).includes(a.inviteSecret));
});

test('concurrent different recipient responses do not conflict and duplicate same response is idempotent', async (t) => {
  const app = await runningApp(t); const a = await app.create(); const b = await app.join(a); const c = await app.join(a);
  const value = (await send(app, b)).data.changeRequest;
  const results = await Promise.all([answer(app, a, value), answer(app, c, value, 'rejected')]);
  assert.deepEqual(results.map((r) => r.status), [200, 200]);
  const before = await snapshot(app, b);
  assert.equal((await answer(app, a, value)).data.duplicate, true);
  assert.equal((await snapshot(app, b)).sequence, before.sequence);
  assert.equal((await answer(app, a, value, 'rejected')).status, 409);
  assert.equal((await answer(app, b, value)).status, 403);
});

test('duplicate creation retains current frame, conflicting id and stale creation revision fail', async (t) => {
  const app = await runningApp(t); const a = await app.create(); await app.join(a);
  const first = await send(app, a); const second = await send(app, a, 2, 1, { ...target, name: '다음 목적지' });
  assert.equal(second.status, 201);
  const duplicate = await send(app, a);
  assert.equal(duplicate.status, 200); assert.equal(duplicate.data.changeRequest.id, id(2));
  assert.equal(duplicate.data.result.requestId, id(1));
  assert.equal((await send(app, a, 1, 0, { ...target, name: '다른 내용' })).status, 409);
  assert.equal((await send(app, a, 3, 0)).status, 409);
  assert.equal((await snapshot(app, a)).requestRevision, 2);
  assert.equal(first.data.changeRequest.destination.name, target.name);
});

test('replacement and exact expiry invalidate old response authority without changing native/legacy target', async (t) => {
  let now = 1_000; const app = await runningApp(t, { clock: () => now }); const a = await app.create(); const b = await app.join(a);
  const first = (await send(app, a)).data.changeRequest;
  const second = (await send(app, a, 2, 1)).data.changeRequest;
  assert.equal((await answer(app, b, first)).status, 409);
  const feed = await events(t, app, b); await feed.next('snapshot');
  now = second.expiresAt; app.cleanupExpired();
  assert.equal((await feed.next('destination_request')).data.changeRequest.status, 'expired');
  assert.equal((await answer(app, b, second)).status, 409);
  const state = await snapshot(app, b); assert.equal(state.revision, 0); assert.equal(state.destination, null);
});

test('membership changes and legacy direct publication invalidate a current change request', async (t) => {
  const app = await runningApp(t); const a = await app.create(); const b = await app.join(a);
  let value = (await send(app, a)).data.changeRequest;
  const c = await app.join(a); assert.equal((await snapshot(app, a)).changeRequest.status, 'invalidated');
  assert.equal((await answer(app, b, value)).status, 409);
  value = (await send(app, b, 2, 1)).data.changeRequest;
  await app.call('DELETE', `${path(c)}/members/me`, { token: c.token });
  assert.equal((await answer(app, a, value)).status, 409);
  value = (await send(app, a, 3, 2)).data.changeRequest;
  await app.call('POST', `${path(a)}/destination`, { token: a.token, body: target });
  assert.equal((await answer(app, b, value)).status, 409);
});

test('auth, own-response schema, target metadata and bounded request history fail closed', async (t) => {
  const app = await runningApp(t, { limits: { maxChangeRequestsPerRoom: 1 } }); const a = await app.create(); const b = await app.join(a); const other = await app.create();
  for (const token of [undefined, a.inviteSecret, other.token]) {
    assert.equal((await app.call('POST', `${path(a)}/destination-requests`, { token,
      body: { id: id(1), expectedRequestRevision: 0, destination: target } })).status, token ? 403 : 401);
  }
  assert.equal((await send(app, a, 1, 0, { name: '좌표만', latitude: 37, longitude: 127 })).status, 400);
  for (const pleos of [{ ...target.pleos, address: ' ' }, { ...target.pleos, poiSubId: ' ' }]) {
    assert.equal((await send(app, a, 1, 0, { ...target, pleos })).status, 400);
  }
  const value = (await send(app, a)).data.changeRequest;
  assert.equal((await app.call('POST', `${path(b)}/destination-requests/${value.id}/responses`, { token: b.token,
    body: { revision: value.revision, response: 'accepted', memberId: a.memberId } })).status, 400);
  assert.equal((await send(app, a, 2, 1)).status, 429);
  assert.equal((await answer(app, b, value)).status, 200);
});
