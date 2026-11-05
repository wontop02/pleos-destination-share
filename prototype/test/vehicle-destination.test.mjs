import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { once } from 'node:events';
import { events, runningApp } from './support.mjs';

// Public SDK documentation example. No real navigation history or private locations.
const destination = {
  name: '42dot', latitude: 37.48544722, longitude: 127.03636666,
  pleos: { poiId: '36khp37hh8gqcm5nqhe5', poiSubId: '0', address: '서울시 강남구 남부순환로 2621',
    poiProvider: '', displayLatitude: null, displayLongitude: null },
};

test('actual authenticated HTTP and SSE preserve optional Pleos metadata to a distinct member', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const feed = await events(t, app, member); await feed.next('snapshot');
  const response = await app.call('POST', `/api/rooms/${host.roomId}/destination`, {
    token: host.token, body: { ...destination, expectedRevision: 0 },
  });
  assert.equal(response.status, 200);
  const received = (await feed.next('destination')).data;
  assert.deepEqual(received.destination.pleos, destination.pleos);
  assert.equal(received.destination.changedBy, host.memberId);
  assert.equal(received.revision, 1);
  const snapshot = (await app.call('GET', `/api/rooms/${host.roomId}`, { token: member.token })).data;
  assert.deepEqual(snapshot.destination, received.destination);
  assert(!JSON.stringify(snapshot).includes(host.token)); assert(!JSON.stringify(snapshot).includes(host.inviteSecret));
});

test('v1 three-field destination response stays unchanged and nullable actual SDK sub-ID is preserved', async (t) => {
  const app = await runningApp(t); const host = await app.create();
  const { pleos, ...legacy } = destination;
  const first = await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: legacy });
  assert.equal(first.status, 200);
  assert.deepEqual(Object.keys(first.data.destination).sort(), ['changedAt', 'changedBy', 'latitude', 'longitude', 'name']);
  const value = { ...destination, pleos: { ...pleos, poiSubId: null, displayLatitude: 37.485, displayLongitude: 127.036 } };
  const second = await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: value });
  assert.equal(second.status, 200); assert.deepEqual(second.data.destination.pleos, value.pleos);
});

test('metadata and revision schema rejects malformed values without changing the destination', async (t) => {
  const app = await runningApp(t); const host = await app.create();
  const variants = [null, [], {}, { ...destination.pleos, poiId: 123 }, { ...destination.pleos, poiId: 'x'.repeat(257) },
    { ...destination.pleos, poiSubId: false }, { ...destination.pleos, address: 'bad\naddress' },
    { ...destination.pleos, address: 'x'.repeat(501) }, { ...destination.pleos, poiProvider: null },
    { ...destination.pleos, displayLatitude: 37 }, { ...destination.pleos, displayLatitude: '37', displayLongitude: 127 },
    { ...destination.pleos, displayLatitude: 91, displayLongitude: 127 }, { ...destination.pleos, surprise: true }];
  for (const pleos of variants) {
    assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/destination`, {
      token: host.token, body: { ...destination, pleos },
    })).status, 400);
  }
  for (const expectedRevision of [-1, 0.5, '0', null, 9_007_199_254_740_992]) {
    assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/destination`, {
      token: host.token, body: { ...destination, expectedRevision },
    })).status, 400);
  }
  const state = (await app.call('GET', `/api/rooms/${host.roomId}`, { token: host.token })).data;
  assert.equal(state.revision, 0); assert.equal(state.destination, null);
});

test('member and outsider cannot publish metadata or clear a host destination', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host); const other = await app.create();
  for (const token of [member.token, other.token, host.inviteSecret]) {
    for (const body of [destination, { destination: null }]) {
      assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token, body })).status, 403);
    }
  }
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}`, { token: host.token })).data.revision, 0);
});

test('concurrent CAS writes commit exactly one revision and stale clear cannot erase newer destination', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const feed = await events(t, app, member); await feed.next('snapshot');
  const results = await Promise.all(['A', 'B'].map((name) => app.call('POST', `/api/rooms/${host.roomId}/destination`, {
    token: host.token, body: { ...destination, name, expectedRevision: 0 },
  })));
  assert.deepEqual(results.map((result) => result.status).sort(), [200, 409]);
  assert.equal((await feed.next('destination')).data.revision, 1);
  const stale = await app.call('POST', `/api/rooms/${host.roomId}/destination`, {
    token: host.token, body: { destination: null, expectedRevision: 0 },
  });
  assert.equal(stale.status, 409); assert.equal(stale.data.code, 'destination_revision_conflict');
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}`, { token: host.token })).data.revision, 1);
  assert.equal(feed.history.filter((event) => event.event === 'destination').length, 1);
});

test('explicit host clear reaches member and reconnect snapshot with a new revision', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: destination });
  const feed = await events(t, app, member); await feed.next('snapshot');
  const response = await app.call('POST', `/api/rooms/${host.roomId}/destination`, {
    token: host.token, body: { destination: null, expectedRevision: 1 },
  });
  assert.equal(response.status, 200); assert.equal((await feed.next('destination')).data.destination, null);
  await feed.close();
  const reconnect = await events(t, app, member); const state = (await reconnect.next('snapshot')).data;
  assert.equal(state.destination, null); assert.equal(state.revision, 2);
});

test('room expiry while reading a delayed vehicle body rechecks authorization before mutation', async (t) => {
  let now = 0; const app = await runningApp(t, { clock: () => now, ttlMs: 10 }); const host = await app.create();
  const bytes = Buffer.from(JSON.stringify(destination));
  const accepted = once(app.server, 'request');
  let complete;
  const response = new Promise((resolve, reject) => {
    const req = request(`${app.base}/api/rooms/${host.roomId}/destination`, { method: 'POST', headers: {
      Authorization: `Bearer ${host.token}`, 'Content-Type': 'application/json', 'Content-Length': bytes.length,
    } }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.on('error', reject); req.write(bytes.subarray(0, 1)); complete = () => req.end(bytes.subarray(1));
  });
  await accepted; now = 11; complete();
  assert.equal(await response, 404); assert.equal(app.stats().rooms, 0);
});
