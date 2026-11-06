import test from 'node:test';
import assert from 'node:assert/strict';
import { events, runningApp } from './support.mjs';

const cafe = { name: '가든서비스', latitude: 37.41255277, longitude: 127.09591666,
  kind: 'pleos_place', source: 'pleos_bookmark',
  pleos: { poiId: '36khp2h36p68cm5nqhec', poiSubId: null, address: '경기도 성남시 수정구 창업로40번길6',
    poiProvider: '', displayLatitude: null, displayLongitude: null } };
const path = (who) => `/api/rooms/${who.roomId}`;
const propose = (app, who, destination, n = 1, revision = 0) => app.call('POST', `${path(who)}/proposals`, {
  token: who.token, body: { id: n.toString(16).padStart(24, '0'), expectedRevision: revision, destination, reason: '선택한 장소로 함께 변경' },
});
const vote = (app, who, p, response = 'accepted') => app.call('POST', `${path(who)}/proposals/${p.id}/responses`, {
  token: who.token, body: { version: p.version, expectedRevision: p.baseRevision, response },
});

test('selected bookmark meaning and immutable source survive authenticated proposal, unanimous commit and reconnect SSE', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const original = structuredClone(cafe);
  const feed = await events(t, app, member); await feed.next('snapshot');
  const response = await propose(app, host, cafe); assert.equal(response.status, 201);
  const pending = (await feed.next('proposal')).data;
  assert.equal(pending.destination, null); assert.equal(pending.revision, 0);
  assert.deepEqual(pending.proposal.destination, original);
  const accepted = await vote(app, member, pending.proposal); assert.equal(accepted.status, 200);
  const commit = (await feed.next('destination')).data;
  assert.deepEqual(commit.proposal.destination, original);
  assert.equal(commit.destination.kind, 'pleos_place'); assert.equal(commit.destination.source, 'pleos_bookmark');
  assert.equal(commit.destination.latitude, original.latitude); assert.deepEqual(commit.destination.pleos, original.pleos);
  await feed.close(); const reopened = await events(t, app, member);
  const snapshot = (await reopened.next('snapshot')).data;
  assert.deepEqual(snapshot.acceptedProposal.destination, original);
  assert.equal(snapshot.destination.kind, 'pleos_place'); assert.equal(snapshot.revision, 1);
  assert.equal((await vote(app, member, pending.proposal)).data.revision, 1);
  assert.deepEqual(cafe, original);
});

test('direct native sharing rejects selected meaning while legacy and explicit exact points stay compatible', async (t) => {
  const app = await runningApp(t); const host = await app.create();
  assert.equal((await app.call('POST', `${path(host)}/destination`, { token: host.token, body: cafe })).status, 400);
  const { kind, source, ...exact } = cafe;
  for (const value of [exact, { ...exact, kind: 'exact_point' }]) {
    const response = await app.call('POST', `${path(host)}/destination`, { token: host.token, body: value });
    assert.equal(response.status, 200); assert(!Object.hasOwn(response.data.destination, 'kind'));
    assert(!Object.hasOwn(response.data.destination, 'source')); assert.deepEqual(response.data.destination.pleos, exact.pleos);
  }
});

test('unknown or missing kind/source, incomplete identity and invalid geography fail closed before proposal creation', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const changes = [
    (v) => { v.kind = 'future_mode'; }, (v) => { v.kind = null; }, (v) => { v.kind = 3; },
    (v) => { delete v.kind; }, (v) => { delete v.source; }, (v) => { v.source = 'external_place_id'; },
    (v) => { v.kind = 'exact_point'; }, (v) => { delete v.pleos; }, (v) => { delete v.pleos.poiProvider; },
    (v) => { delete v.pleos.poiSubId; }, (v) => { v.pleos.poiId = ''; }, (v) => { v.pleos.poiId = ' id '; },
    (v) => { v.pleos.poiSubId = ''; }, (v) => { v.pleos.poiProvider = 'Google'; }, (v) => { v.pleos.address = ''; },
    (v) => { v.latitude = 91; }, (v) => { v.longitude = null; }, (v) => { v.name = ` ${v.name}`; },
  ];
  for (const [index, change] of changes.entries()) {
    const value = structuredClone(cafe); change(value);
    const response = await propose(app, host, value, index + 1);
    assert.equal(response.status, 400, `invalid metadata case ${index}`);
  }
  const snapshot = (await app.call('GET', path(member), { token: member.token })).data;
  assert.equal(snapshot.revision, 0); assert.equal(snapshot.destination, null); assert.equal(snapshot.proposal, null);
});

test('reject, expiry and stale acceptance never commit a selected place; later candidates preserve accepted meaning', async (t) => {
  let now = 1000; const app = await runningApp(t, { clock: () => now });
  const host = await app.create(); const member = await app.join(host);
  let p = (await propose(app, member, cafe)).data.proposal;
  assert.equal((await vote(app, host, p, 'rejected')).data.revision, 0);
  p = (await propose(app, host, cafe, 2)).data.proposal;
  now = p.expiresAt; app.cleanupExpired();
  assert.equal((await vote(app, member, p)).status, 409);
  p = (await propose(app, member, cafe, 3)).data.proposal;
  const accepted = await vote(app, host, p); assert.equal(accepted.data.revision, 1);
  const second = { ...cafe, name: '청계산담백미', latitude: 37.40931388, longitude: 127.09780277,
    pleos: { ...cafe.pleos, poiId: 'another-public-place' } };
  assert.equal((await propose(app, host, second, 4, 1)).data.proposal.status, 'pending');
  const snapshot = (await app.call('GET', path(member), { token: member.token })).data;
  assert.deepEqual(snapshot.acceptedProposal.destination, cafe); assert.equal(snapshot.destination.kind, 'pleos_place');
  assert.equal(snapshot.destination.latitude, cafe.latitude); assert.equal(snapshot.revision, 1);
});
