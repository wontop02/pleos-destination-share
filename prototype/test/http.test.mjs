import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { MAX_WAV_BYTES } from '../wav.mjs';
import { AutoPlayQueue } from '../public/helpers.js';
import { events, fictionalWav, messageId, runningApp } from './support.mjs';

const destination = { name: '가상 쉼터 & 동행 #1', latitude: 37.5209436, longitude: 127.1230074 };

test('two independent members get separate capabilities; invite secret cannot read a room', async (t) => {
  const app = await runningApp(t);
  const host = await app.create(); const first = await app.join(host, '참여자 A'); const second = await app.join(host, '참여자 B');
  assert.equal(new Set([host.token, host.inviteSecret, first.token, second.token]).size, 4);
  assert.notEqual(first.memberId, second.memberId);
  assert.equal(first.role, 'member'); assert.equal(host.role, 'host');
  const snapshot = await app.call('GET', `/api/rooms/${host.roomId}`, { token: first.token });
  assert.equal(snapshot.status, 200); assert.equal(snapshot.data.self.name, '참여자 A'); assert.equal(snapshot.data.members.length, 3);
  assert.equal(snapshot.data.destination, null);
  assert(!JSON.stringify(snapshot.data).includes(host.token)); assert(!JSON.stringify(snapshot.data).includes(host.inviteSecret));
  for (const token of [undefined, host.inviteSecret, randomBytes(32).toString('base64url')]) {
    assert([401, 403].includes((await app.call('GET', `/api/rooms/${host.roomId}`, { token })).status));
  }
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/members`, { token: host.token, body: { name: '권한 혼동' } })).status, 403);
});

test('authenticated SSE delivers destination changes to another member and reconnect sends latest snapshot', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const feed = await events(t, app, member);
  const initial = await feed.next('snapshot'); assert.equal(initial.data.revision, 0);
  assert.equal(initial.data.self.id, member.memberId);
  const update = await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: destination });
  assert.equal(update.status, 200);
  const received = await feed.next('destination');
  assert.equal(received.data.destination.name, destination.name); assert.equal(received.data.revision, 1);
  await feed.close();
  await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: { ...destination, name: '변경 후 가상 목적지' } });
  const reconnect = await events(t, app, member);
  const latest = await reconnect.next('snapshot');
  assert.equal(latest.data.revision, 2); assert.equal(latest.data.destination.name, '변경 후 가상 목적지');
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/events?token=${member.token}`)).status, 400);
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/events`)).status, 401);
});

test('host permission, room isolation, and destination validation reject unauthorized or malformed changes', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host); const other = await app.create('다른 대표');
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: member.token, body: destination })).status, 403);
  assert.equal((await app.call('DELETE', `/api/rooms/${host.roomId}`, { token: member.token })).status, 403);
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}`, { token: other.token })).status, 403);
  for (const body of [null, [], { ...destination, latitude: '37' }, { ...destination, latitude: 91 },
    { ...destination, longitude: -181 }, { ...destination, name: ' ' }, { ...destination, name: 'x'.repeat(101) },
    { ...destination, name: 'bad\nname' }, { ...destination, surprise: true }]) {
    assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body })).status, 400);
  }
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}`, { token: host.token })).data.revision, 0);
});

test('real WAV upload, SSE, authenticated byte-for-byte receiver download, idempotency and conflict', async (t) => {
  let now = 1_800_000_000_000;
  const app = await runningApp(t, { clock: () => now });
  const host = await app.create(); const member = await app.join(host); const outsider = await app.create('다른 방');
  const feed = await events(t, app, member); await feed.next('snapshot');
  const bytes = fictionalWav(32_000);
  const first = await app.upload(host, 1, bytes);
  assert.equal(first.status, 201); assert.equal(first.data.message.durationMs, 2000);
  assert.equal(first.data.message.acceptedAt, now); assert.equal(first.data.message.autoPlayUntil, now + 30_000);
  const received = await feed.next('voice'); assert.equal(received.data.message.id, first.data.message.id);
  const audioPath = `/api/rooms/${host.roomId}/voice/${first.data.message.id}/audio`;
  const download = await app.call('GET', audioPath, { token: member.token });
  assert.equal(download.status, 200); assert.deepEqual(download.data, bytes);
  assert.equal((await app.call('GET', audioPath)).status, 401);
  assert.equal((await app.call('GET', audioPath, { token: outsider.token })).status, 403);
  assert.equal((await app.call('GET', `/api/rooms/${outsider.roomId}/voice/${first.data.message.id}/audio`, { token: outsider.token })).status, 404);
  now += 1000;
  const retry = await app.upload(host, 1, bytes);
  assert.equal(retry.status, 200); assert.equal(retry.data.duplicate, true); assert.deepEqual(retry.data.message, first.data.message);
  assert.equal((await app.upload(host, 1, fictionalWav(32_000, 440))).status, 409);
  // A destination event is a stream ordering barrier: a duplicated voice before it would already be received.
  await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: destination }); await feed.next('destination');
  assert.equal(feed.history.filter((event) => event.event === 'voice').length, 1);
  const memberSend = await app.upload(member, 1, bytes);
  assert.equal(memberSend.status, 201); assert.notEqual(memberSend.data.message.id, first.data.message.id);
  assert.equal((await feed.next('voice')).data.message.sender.id, member.memberId);
  const history = (await app.call('GET', `/api/rooms/${host.roomId}`, { token: member.token })).data.voiceMessages;
  assert.equal(history.length, 2); assert(history[0].sequence < history[1].sequence);
});

test('stale history remains manually downloadable but never becomes a fresh automatic event on reconnect', async (t) => {
  let now = 100_000;
  const app = await runningApp(t, { clock: () => now }); const host = await app.create(); const member = await app.join(host);
  const voice = await app.upload(host, 1); now += 30_001;
  const feed = await events(t, app, member); const snapshot = (await feed.next('snapshot')).data;
  assert.equal(snapshot.voiceMessages[0].autoPlayUntil, 130_000);
  const queue = new AutoPlayQueue({ selfId: member.memberId, now: () => now }); queue.setEnabled(true); queue.offer(snapshot.voiceMessages);
  assert.equal(queue.take(), null);
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/voice/${voice.data.message.id}/audio`, { token: member.token })).status, 200);
});

test('reconnect streams a full bounded one-hundred-message history without dropping the initial snapshot', async (t) => {
  const app = await runningApp(t, { limits: { uploadsPerMinute: 100 } }); const host = await app.create('가'.repeat(40));
  for (let index = 0; index < 100; index += 1) assert.equal((await app.upload(host, index)).status, 201);
  const feed = await events(t, app, host); const snapshot = (await feed.next('snapshot')).data;
  assert.equal(snapshot.voiceMessages.length, 100); assert.equal(new Set(snapshot.voiceMessages.map((item) => item.id)).size, 100);
  await app.call('POST', `/api/rooms/${host.roomId}/destination`, { token: host.token, body: destination });
  assert.equal((await feed.next('destination')).data.revision, 1);
});

test('close terminates event streams, invalidates every capability and deletes stored audio bytes', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const feed = await events(t, app, member); await feed.next('snapshot');
  const voice = await app.upload(member, 1); await feed.next('voice'); assert(app.stats().audioBytes > 0);
  assert.equal((await app.call('DELETE', `/api/rooms/${host.roomId}`, { token: host.token })).status, 200);
  assert.equal((await feed.next('closed')).data.reason, 'closed');
  for (const token of [host.token, member.token]) assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/voice/${voice.data.message.id}/audio`, { token })).status, 404);
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/members`, { token: host.inviteSecret, body: { name: '늦은 참여' } })).status, 404);
  assert.deepEqual(app.stats(), { rooms: 0, members: 0, messages: 0, streams: 0, audioBytes: 0 });
});

test('injected six-hour expiry removes bytes and terminates streams without requiring a download', async (t) => {
  let now = 0; const app = await runningApp(t, { clock: () => now });
  const host = await app.create(); const member = await app.join(host); const feed = await events(t, app, member); await feed.next('snapshot');
  const sent = await app.upload(host, 1); await feed.next('voice');
  now = 6 * 60 * 60 * 1000; app.cleanupExpired();
  assert.equal((await feed.next('closed')).data.reason, 'expired'); assert.equal(app.stats().audioBytes, 0);
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/voice/${sent.data.message.id}/audio`, { token: host.token })).status, 404);
});

test('request-triggered and periodic expiry also clean up unused rooms', async (t) => {
  let now = 1000; const app = await runningApp(t, { clock: () => now, ttlMs: 50, cleanupIntervalMs: 5 });
  const first = await app.create(); const stream = await events(t, app, first); await stream.next('snapshot');
  await app.upload(first, 1); now += 51;
  assert.equal((await stream.next('closed')).data.reason, 'expired'); assert.equal(app.stats().audioBytes, 0);
  const second = await app.create(); now += 51;
  assert.equal((await app.call('GET', `/api/rooms/${second.roomId}`, { token: second.token })).status, 404);
  assert.equal(app.stats().rooms, 0);
});

test('ten uploads per rolling minute per sender; duplicates and rejected WAVs consume no quota', async (t) => {
  let now = 100_000; const app = await runningApp(t, { clock: () => now }); const host = await app.create(); const member = await app.join(host);
  assert.equal((await app.upload(host, 999, Buffer.from('not a WAV'))).status, 422);
  const bytes = fictionalWav();
  for (let index = 0; index < 10; index += 1) assert.equal((await app.upload(host, index, bytes)).status, 201);
  assert.equal((await app.upload(host, 0, bytes)).status, 200);
  const limited = await app.upload(host, 10, bytes); assert.equal(limited.status, 429); assert.equal(limited.data.code, 'voice_rate_limit');
  assert.equal(limited.response.headers.get('retry-after'), '60');
  assert.equal((await app.upload(member, 0, bytes)).status, 201);
  now += 59_999; assert.equal((await app.upload(host, 10, bytes)).status, 429);
  now += 1; assert.equal((await app.upload(host, 10, bytes)).status, 201);
});

test('bounded HTTP payload rejects Content-Length and chunked overflow before storing audio', async (t) => {
  const app = await runningApp(t); const host = await app.create();
  assert.equal((await app.upload(host, 1, Buffer.alloc(MAX_WAV_BYTES + 1))).status, 413);
  const result = await new Promise((fulfill, fail) => {
    const request = httpRequest(`${app.base}/api/rooms/${host.roomId}/voice`, { method: 'POST', headers: {
      Authorization: `Bearer ${host.token}`, 'Content-Type': 'audio/wav', 'X-Client-Message-Id': messageId(2), 'Transfer-Encoding': 'chunked',
    } }, (response) => {
      let data = ''; response.on('data', (chunk) => { data += chunk; }); response.on('end', () => fulfill({ status: response.statusCode, data: JSON.parse(data) }));
    });
    request.on('error', fail); request.write(Buffer.alloc(MAX_WAV_BYTES)); request.end(Buffer.alloc(1));
  });
  assert.equal(result.status, 413); assert.equal(app.stats().audioBytes, 0); assert.equal(app.stats().messages, 0);
});

test('simultaneous raw audio bodies are bounded and capacity is released when a transfer finishes', async (t) => {
  const app = await runningApp(t, { limits: { maxInflightUploads: 1 } }); const host = await app.create();
  const bytes = fictionalWav(); const accepted = once(app.server, 'request');
  let complete;
  const result = new Promise((fulfill, fail) => {
    const request = httpRequest(`${app.base}/api/rooms/${host.roomId}/voice`, { method: 'POST', headers: {
      Authorization: `Bearer ${host.token}`, 'Content-Type': 'audio/wav', 'X-Client-Message-Id': messageId(1),
    } }, (response) => { response.resume(); response.on('end', () => fulfill(response.statusCode)); });
    request.on('error', fail); request.write(bytes.subarray(0, 44)); complete = () => request.end(bytes.subarray(44));
  });
  await accepted;
  const capped = await app.upload(host, 2, bytes); assert.equal(capped.status, 429); assert.equal(capped.data.code, 'upload_concurrency_limit');
  complete(); assert.equal(await result, 201); assert.equal((await app.upload(host, 2, bytes)).status, 201);
});

test('global audio storage cap spans rooms; duplicates are free; closing a room releases capacity', async (t) => {
  const bytes = fictionalWav(); const app = await runningApp(t, { limits: { maxAudioBytes: bytes.length * 2 } });
  const first = await app.create(); const second = await app.create();
  assert.equal((await app.upload(first, 1, bytes)).status, 201); assert.equal((await app.upload(second, 1, bytes)).status, 201);
  const full = await app.upload(second, 2, bytes); assert.equal(full.status, 507); assert.equal(full.data.code, 'audio_storage_limit');
  assert.equal((await app.upload(first, 1, bytes)).status, 200); assert.equal(app.stats().audioBytes, bytes.length * 2);
  await app.call('DELETE', `/api/rooms/${first.roomId}`, { token: first.token });
  assert.equal((await app.upload(second, 2, bytes)).status, 201); assert.equal(app.stats().audioBytes, bytes.length * 2);
});

test('room, member, history and concurrent stream caps are enforced', async (t) => {
  const app = await runningApp(t, { limits: { maxRooms: 1, maxMembers: 2, maxMessagesPerRoom: 2, maxStreamsPerMember: 1 } });
  const host = await app.create(); const member = await app.join(host);
  assert.equal((await app.call('POST', '/api/rooms', { body: { name: '초과' } })).status, 429);
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/members`, { token: host.inviteSecret, body: { name: '초과' } })).status, 429);
  const stream = await events(t, app, member); await stream.next('snapshot');
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/events`, { token: member.token })).status, 429);
  assert.equal((await app.upload(host, 1)).status, 201); assert.equal((await app.upload(host, 2)).status, 201);
  const capped = await app.upload(host, 3); assert.equal(capped.status, 507); assert.equal(capped.data.code, 'history_limit');
  assert.equal((await app.upload(host, 1)).status, 200);
  await app.call('DELETE', `/api/rooms/${host.roomId}`, { token: host.token });
  assert.equal((await app.call('POST', '/api/rooms', { body: { name: '새 공유방' } })).status, 201);
});

test('member leave invalidates only that token, stops its stream and preserves history for remaining members', async (t) => {
  const app = await runningApp(t); const host = await app.create(); const member = await app.join(host);
  const stream = await events(t, app, member); await stream.next('snapshot'); const sent = await app.upload(member, 1); await stream.next('voice');
  assert.equal((await app.call('DELETE', `/api/rooms/${host.roomId}/members/me`, { token: member.token })).status, 200);
  assert.equal((await stream.next('closed')).data.reason, 'left');
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}`, { token: member.token })).status, 403);
  assert.equal((await app.call('GET', `/api/rooms/${host.roomId}/voice/${sent.data.message.id}/audio`, { token: host.token })).status, 200);
  assert.equal((await app.call('DELETE', `/api/rooms/${host.roomId}/members/me`, { token: host.token })).status, 403);
  assert.equal(app.stats().members, 1);
});

test('same-origin browser mutations and loopback host checks; native requests may omit Origin', async (t) => {
  const app = await runningApp(t);
  for (const headers of [{ Origin: 'https://attacker.invalid' }, { Origin: 'null' }, { 'Sec-Fetch-Site': 'same-site' }, { 'Sec-Fetch-Site': 'cross-site' }]) {
    assert.equal((await app.call('POST', '/api/rooms', { body: { name: '차단' }, headers })).status, 403);
  }
  const native = await app.call('POST', '/api/rooms', { body: { name: '네이티브 호출' } }); assert.equal(native.status, 201);
  assert.equal((await app.call('POST', '/api/rooms', { body: { name: '브라우저 호출' }, headers: { Origin: app.base, 'Sec-Fetch-Site': 'same-origin' } })).status, 201);
  const forgedHostStatus = await new Promise((fulfill, fail) => {
    const request = httpRequest(`${app.base}/api/rooms/${native.data.roomId}`, { headers: { Host: 'attacker.invalid', Authorization: `Bearer ${native.data.token}` } }, (response) => {
      response.resume(); response.on('end', () => fulfill(response.statusCode));
    });
    request.on('error', fail); request.end();
  });
  assert.equal(forgedHostStatus, 403);
});

test('malformed room input, MIME, JSON, ids and paths are rejected without reflecting credentials', async (t) => {
  const app = await runningApp(t);
  for (const body of [null, [], {}, { name: '' }, { name: 'x'.repeat(41) }, { name: 'new\nline' }, { name: 'a', extra: true }]) {
    assert.equal((await app.call('POST', '/api/rooms', { body })).status, 400);
  }
  assert.equal((await app.call('POST', '/api/rooms', { body: Buffer.from('{'), headers: { 'Content-Type': 'application/json' } })).status, 400);
  assert.equal((await app.call('POST', '/api/rooms', { body: Buffer.from('{}'), headers: { 'Content-Type': 'text/plain' } })).status, 415);
  assert.equal((await app.call('POST', '/api/rooms', { body: Buffer.alloc(4097), headers: { 'Content-Type': 'application/json' } })).status, 413);
  assert.equal(app.stats().rooms, 0);
  const host = await app.create();
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/voice`, { token: host.token, body: fictionalWav(), headers: { 'Content-Type': 'audio/wav' } })).status, 400);
  assert.equal((await app.call('POST', `/api/rooms/${host.roomId}/voice`, { token: host.token, body: fictionalWav(), headers: { 'Content-Type': 'application/json', 'X-Client-Message-Id': messageId(1) } })).status, 415);
  const query = await app.call('GET', `/api/rooms/${host.roomId}?token=${host.token}`);
  assert.equal(query.status, 400); assert(!JSON.stringify(query.data).includes(host.token));
  assert.equal((await app.call('PUT', `/api/rooms/${host.roomId}`, { token: host.token })).status, 405);
  assert.equal((await app.call('GET', '/api/rooms/bad-id')).status, 404);
});

test('static browser surface has restrictive headers and all modules are served locally', async (t) => {
  const app = await runningApp(t);
  for (const path of ['/', '/app.js', '/helpers.js', '/audio.js', '/recording-worklet.js', '/style.css']) {
    const response = await fetch(`${app.base}${path}`); assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store'); assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
    assert(response.headers.get('content-security-policy').includes("frame-ancestors 'none'"));
    const text = await response.text(); assert(text.length > 100);
    if (path === '/') { assert(text.includes('lang="ko"')); assert(!text.includes('src="https://')); assert(text.includes('화면 잠금')); }
  }
});
