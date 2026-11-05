import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { once } from 'node:events';
import { events, runningApp } from './support.mjs';

const first = { name: '42dot', latitude: 37.41289678, longitude: 127.09383464,
  pleos: { poiId: '36khp37hh8gqcm5nqhe5', poiSubId: null, address: '수정구 시흥동', poiProvider: '', displayLatitude: null, displayLongitude: null } };
const second = { ...first, name: '소프트웨어드림센터', latitude: 37.41275833, longitude: 127.09481944,
  pleos: { ...first.pleos, poiId: '36khp2hdj2cfcm5nqhe3' } };
const proposalId = (n = 1) => n.toString(16).padStart(24, '0');
const roomPath = (member) => `/api/rooms/${member.roomId}`;
const propose = (app, who, n = 1, expectedRevision = 0, destination = second) => app.call('POST', `${roomPath(who)}/proposals`, {
  token: who.token, body: { id: proposalId(n), expectedRevision, destination, reason: '함께 변경해요.' },
});
const respond = (app, who, proposal, response = 'accepted', version = proposal.version) => app.call('POST',
  `${roomPath(who)}/proposals/${proposal.id}/responses`, { token: who.token, body: { version, expectedRevision: proposal.baseRevision, response } });
const snapshot = async (app, who) => (await app.call('GET', roomPath(who), { token: who.token })).data;

// The body begins under a valid identity; its remaining bytes arrive after a competing mutation.
async function delayed(app, who, suffix, body) {
  const bytes = Buffer.from(JSON.stringify(body)); let finish;
  const seen = once(app.server, 'request');
  const result = new Promise((resolve, reject) => {
    const req = request(`${app.base}${roomPath(who)}${suffix}`, { method: 'POST', headers: {
      Authorization: `Bearer ${who.token}`, 'Content-Type': 'application/json', 'Content-Length': bytes.length,
    } }, (res) => { let text = ''; res.on('data', (chunk) => { text += chunk; }); res.on('end', () => resolve({ status: res.statusCode, data: JSON.parse(text) })); });
    req.on('error', reject); req.write(bytes.subarray(0, 1)); finish = () => req.end(bytes.subarray(1));
  });
  await seen; return { finish, result };
}

test('authenticated proposal stays separate; unanimous acceptance is one atomic legacy-compatible destination frame', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  await app.call('POST', `${roomPath(host)}/destination`, { token: host.token, body: first });
  const feed = await events(t, app, member); await feed.next('snapshot');
  const created = await propose(app, host, 1, 1); assert.equal(created.status, 201);
  const pending = (await feed.next('proposal')).data;
  assert.equal(pending.revision, 1); assert.equal(pending.destination.name, first.name);
  assert.equal(pending.proposal.status, 'pending'); assert.equal(pending.proposal.responses[0].response, 'accepted');
  assert.equal(pending.proposal.responses[1].response, 'pending');
  const accepted = await respond(app, member, pending.proposal); assert.equal(accepted.status, 200);
  const commit = (await feed.next('destination')).data;
  assert.equal(commit.sequence, pending.sequence + 1); assert.equal(commit.revision, 2);
  assert.equal(commit.proposal.acceptedRevision, 2); assert.equal(commit.proposal.status, 'accepted');
  assert.deepEqual(commit.destination.pleos, second.pleos);
  assert.equal(commit.destination.changedBy, host.memberId);
  assert.deepEqual((await snapshot(app, member)).proposal, commit.proposal);
  assert(!JSON.stringify(commit).includes(host.token)); assert(!JSON.stringify(commit).includes(host.inviteSecret));
  assert.equal(feed.history.filter((event) => event.event === 'destination').length, 1);
  await feed.close(); const reconnect = await events(t, app, member);
  const restored = (await reconnect.next('snapshot')).data;
  assert.equal(restored.revision, 2); assert.equal(restored.proposal.id, pending.proposal.id);
});

test('member proposes and host accepts; duplicate creation/vote never increments destination again', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const created = await propose(app, member); const p = created.data.proposal;
  const duplicate = await propose(app, member); assert.equal(duplicate.status, 200); assert.equal(duplicate.data.duplicate, true);
  assert.equal(duplicate.data.sequence, created.data.sequence);
  const votes = await Promise.all([respond(app, host, p), respond(app, host, p)]);
  assert.deepEqual(votes.map((v) => v.status), [200, 200]); assert.equal(votes.filter((v) => v.data.duplicate).length, 1);
  assert.equal((await snapshot(app, member)).revision, 1);
  assert.equal((await respond(app, host, p, 'rejected')).status, 409);
  assert.equal((await propose(app, member)).data.proposal.status, 'accepted');
  assert.equal((await snapshot(app, host)).revision, 1);
});

test('three members require every remaining vote; stale version is fenced and explicit refreshed vote commits', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const b = await app.join(host); const c = await app.join(host);
  const p = (await propose(app, b)).data.proposal;
  const firstVote = await respond(app, host, p);
  assert.equal(firstVote.data.proposal.status, 'pending'); assert.equal(firstVote.data.revision, 0); assert.equal(firstVote.data.destination, null);
  assert.equal((await respond(app, c, p)).status, 409);
  const finalVote = await respond(app, c, firstVote.data.proposal);
  assert.equal(finalVote.data.revision, 1); assert.equal(finalVote.data.proposal.status, 'accepted');
  assert.equal((await respond(app, host, p)).data.duplicate, true);
  assert.equal((await snapshot(app, b)).revision, 1);
});

test('simultaneous accept and reject is serialized; only the first response is authoritative', async (t) => {
  for (let index = 0; index < 5; index++) {
    const app = await runningApp(t); const host = await app.create(); const b = await app.join(host);
    const p = (await propose(app, host)).data.proposal;
    const results = await Promise.all([respond(app, b, p), respond(app, b, p, 'rejected')]);
    assert.deepEqual(results.map((v) => v.status).sort(), [200, 409]);
    const state = await snapshot(app, b); const accepted = state.proposal.status === 'accepted';
    assert.equal(state.revision, accepted ? 1 : 0); assert.equal(state.destination?.name ?? null, accepted ? second.name : null);
  }
});

test('reject and exact 120-second expiry preserve shared destination and revision; cleanup broadcasts expiry', async (t) => {
  let now = 1_000; const app = await runningApp(t, { clock: () => now }); const host = await app.create(); const b = await app.join(host);
  await app.call('POST', `${roomPath(host)}/destination`, { token: host.token, body: first });
  let p = (await propose(app, host, 1, 1)).data.proposal;
  assert.equal((await respond(app, b, p, 'rejected')).data.proposal.status, 'rejected');
  p = (await propose(app, b, 2, 1)).data.proposal;
  const feed = await events(t, app, host); await feed.next('snapshot');
  now = p.expiresAt; app.cleanupExpired();
  const expired = (await feed.next('proposal')).data;
  assert.equal(expired.proposal.status, 'expired'); assert.equal(expired.revision, 1); assert.equal(expired.destination.name, first.name);
  assert.equal((await respond(app, host, p)).status, 409);
  assert.equal((await snapshot(app, b)).revision, 1);
});

test('acceptance just before expiry commits, while a delayed vote finishing at expiry cannot commit', async (t) => {
  let now = 0; const app = await runningApp(t, { clock: () => now }); const host = await app.create(); const b = await app.join(host);
  let p = (await propose(app, host)).data.proposal;
  now = 119_999; assert.equal((await respond(app, b, p)).data.revision, 1);
  p = (await propose(app, host, 2, 1, first)).data.proposal;
  const pending = await delayed(app, b, `/proposals/${p.id}/responses`, { version: p.version, expectedRevision: 1, response: 'accepted' });
  now = p.expiresAt; pending.finish(); assert.equal((await pending.result).status, 409);
  assert.equal((await snapshot(app, host)).revision, 1);
});

test('join, leave, and newer direct destination invalidate pending proposals without a candidate commit', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const b = await app.join(host);
  let p = (await propose(app, host)).data.proposal;
  const c = await app.join(host); let state = await snapshot(app, host);
  assert.equal(state.proposal.status, 'invalidated'); assert.equal(state.proposal.invalidatedBy, 'membership_changed'); assert.equal(state.revision, 0);
  assert.equal((await respond(app, b, p)).status, 409);
  p = (await propose(app, b, 2)).data.proposal;
  await app.call('DELETE', `${roomPath(c)}/members/me`, { token: c.token });
  assert.equal((await snapshot(app, host)).proposal.status, 'invalidated');
  p = (await propose(app, host, 3)).data.proposal;
  await app.call('POST', `${roomPath(host)}/destination`, { token: host.token, body: { ...first, expectedRevision: 0 } });
  state = await snapshot(app, b); assert.equal(state.proposal.invalidatedBy, 'destination_changed');
  assert.equal(state.destination.name, first.name); assert.equal(state.revision, 1);
  assert.equal((await respond(app, b, p)).status, 409);
});

test('old leader publication racing acceptance cannot overwrite the committed target', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const b = await app.join(host);
  const p = (await propose(app, host)).data.proposal;
  const delayedOld = await delayed(app, host, '/destination', { ...first, expectedRevision: 0 });
  assert.equal((await respond(app, b, p)).status, 200);
  delayedOld.finish(); assert.equal((await delayedOld.result).status, 409);
  const state = await snapshot(app, host); assert.equal(state.revision, 1); assert.equal(state.destination.name, second.name);
});

test('delayed vote reauthenticates after leave, room closure and room expiry', async (t) => {
  for (const mode of ['leave', 'close', 'expiry']) {
    let now = 0; const app = await runningApp(t, { clock: () => now, ttlMs: 1_000 });
    const host = await app.create(); const b = await app.join(host); const p = (await propose(app, host)).data.proposal;
    const pending = await delayed(app, b, `/proposals/${p.id}/responses`, { version: p.version, expectedRevision: 0, response: 'accepted' });
    if (mode === 'leave') await app.call('DELETE', `${roomPath(b)}/members/me`, { token: b.token });
    else if (mode === 'close') await app.call('DELETE', roomPath(host), { token: host.token });
    else now = 1_000;
    pending.finish(); assert.equal((await pending.result).status, mode === 'leave' ? 403 : 404);
    if (mode === 'leave') assert.equal((await snapshot(app, host)).revision, 0);
    else assert.equal(app.stats().rooms, 0);
  }
});

test('delayed creation cannot retain a departed identity or an expired room', async (t) => {
  for (const expire of [false, true]) {
    let now = 0; const app = await runningApp(t, { clock: () => now, ttlMs: 1_000 });
    const host = await app.create(); const b = await app.join(host);
    const pending = await delayed(app, b, '/proposals', { id: proposalId(), expectedRevision: 0, destination: second, reason: '함께 변경' });
    if (expire) now = 1_000; else await app.call('DELETE', `${roomPath(b)}/members/me`, { token: b.token });
    pending.finish(); assert.equal((await pending.result).status, expire ? 404 : 403);
  }
});

test('authentication, own-vote schema, different room and stale base revision cannot grant consensus', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const b = await app.join(host); const outsider = await app.create();
  for (const token of [undefined, host.inviteSecret, outsider.token]) {
    assert.equal((await app.call('POST', `${roomPath(host)}/proposals`, { token, body: { id: proposalId(), expectedRevision: 0, destination: second, reason: '변경' } })).status, token ? 403 : 401);
  }
  const p = (await propose(app, host)).data.proposal;
  assert.equal((await respond(app, outsider, p)).status, 404);
  assert.equal((await app.call('POST', `${roomPath(b)}/proposals/${p.id}/responses`, { token: b.token,
    body: { version: p.version, expectedRevision: 0, response: 'accepted', memberId: host.memberId } })).status, 400);
  assert.equal((await app.call('POST', `${roomPath(b)}/proposals/${p.id}/responses`, { token: b.token,
    body: { version: p.version, expectedRevision: 1, response: 'accepted' } })).status, 409);
  assert.equal((await snapshot(app, host)).revision, 0);
});

test('one pending, bounded input/history, changed ID replay and malformed candidates are rejected', async (t) => {
  const app = await runningApp(t, { limits: { maxProposalsPerRoom: 2 } }); const host = await app.create(); const b = await app.join(host);
  const p = (await propose(app, host)).data.proposal;
  assert.equal((await propose(app, b, 2)).status, 409);
  assert.equal((await propose(app, host, 1, 0, first)).data.code, 'proposal_id_conflict');
  await respond(app, b, p, 'rejected');
  const invalid = [null, { ...second, pleos: undefined }, { ...second, pleos: { ...second.pleos, poiId: '' } },
    { ...second, pleos: { ...second.pleos, poiSubId: '' } }];
  for (const destination of invalid) assert.equal((await propose(app, host, 2, 0, destination)).status, 400);
  for (const reason of ['', 'x'.repeat(201), 'bad\nreason', 123]) {
    assert.equal((await app.call('POST', `${roomPath(host)}/proposals`, { token: host.token,
      body: { id: proposalId(2), expectedRevision: 0, destination: first, reason } })).status, 400);
  }
  assert.equal((await app.call('POST', `${roomPath(host)}/proposals`, { token: host.token,
    body: { id: proposalId(2), expectedRevision: 0, destination: first, reason: 'x'.repeat(5000) } })).status, 413);
  const secondProposal = (await propose(app, b, 2)).data.proposal;
  await respond(app, host, secondProposal, 'rejected');
  assert.equal((await propose(app, host, 3)).data.code, 'proposal_limit');
  assert.equal((await propose(app, host, 1)).data.duplicate, true);
  assert.equal((await snapshot(app, b)).revision, 0);
});
