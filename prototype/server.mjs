import { createServer } from 'node:http';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { inspectWav, MAX_WAV_BYTES } from './wav.mjs';
import { createPttAdapter, PttError, pttMutation } from './ptt/adapter.mjs';
import { loadApnsProviderFromEnvironment } from './ptt/apns.mjs';

const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/audio.js', ['audio.js', 'text/javascript; charset=utf-8']],
  ['/helpers.js', ['helpers.js', 'text/javascript; charset=utf-8']],
  ['/recording-worklet.js', ['recording-worklet.js', 'text/javascript; charset=utf-8']],
  ['/style.css', ['style.css', 'text/css; charset=utf-8']],
]);
const defaults = Object.freeze({
  maxRooms: 100, maxMembers: 20, maxMessagesPerRoom: 100,
  maxAudioBytes: 32 * 1024 * 1024, maxWavBytes: MAX_WAV_BYTES,
  uploadsPerMinute: 10, maxStreamsPerMember: 2, maxInflightUploads: 8,
});
const secret = () => randomBytes(32).toString('base64url');
const id = () => randomBytes(12).toString('hex');
const same = (actual, expected) => typeof actual === 'string' && typeof expected === 'string' &&
  Buffer.byteLength(actual) === Buffer.byteLength(expected) &&
  timingSafeEqual(Buffer.from(actual), Buffer.from(expected));

class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const reject = (status, code, message) => { throw new ApiError(status, code, message); };
const objectWith = (value, keys) => value !== null && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));
function personName(body) {
  if (!objectWith(body, ['name']) || typeof body.name !== 'string' ||
      !body.name.trim() || body.name.length > 40 || /[\u0000-\u001f\u007f]/u.test(body.name)) {
    reject(400, 'invalid_name', '참여자 이름을 1–40자로 입력해 주세요.');
  }
  return body.name.trim();
}
function bearer(req) {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '');
  if (!match) reject(401, 'authentication_required', '공유방 인증이 필요합니다.');
  return match[1];
}
function bodyBytes(req, limit) {
  return new Promise((fulfill, fail) => {
    const declared = req.headers['content-length'];
    if (declared !== undefined && (!/^\d+$/.test(declared) || Number(declared) > limit)) {
      req.resume();
      fail(new ApiError(413, 'payload_too_large', '전송 크기가 상한을 넘었습니다.'));
      return;
    }
    let size = 0;
    let chunks = [];
    let finished = false;
    req.on('data', (chunk) => {
      if (finished) return;
      size += chunk.length;
      if (size > limit) {
        finished = true;
        chunks = [];
        fail(new ApiError(413, 'payload_too_large', '전송 크기가 상한을 넘었습니다.'));
      } else chunks.push(chunk);
    });
    req.on('end', () => {
      if (finished) return;
      finished = true;
      fulfill(Buffer.concat(chunks, size));
    });
    const aborted = () => {
      if (finished) return;
      finished = true;
      chunks = [];
      fail(new ApiError(400, 'request_aborted', '전송이 중단되었습니다.'));
    };
    req.on('aborted', aborted);
    req.on('error', aborted);
  });
}
async function jsonBody(req) {
  if (!/^application\/json(?:\s*;.*)?$/i.test(req.headers['content-type'] ?? '')) {
    reject(415, 'json_required', 'JSON 요청이 필요합니다.');
  }
  const bytes = await bodyBytes(req, 4096);
  try { return JSON.parse(bytes.toString('utf8')); }
  catch { reject(400, 'invalid_json', '입력 형식이 올바르지 않습니다.'); }
}

/** Local, in-memory HTTP service. Inject a clock and limits for deterministic integration tests. */
export function createPrototypeServer({ clock = Date.now, ttlMs = 6 * 60 * 60 * 1000,
  cleanupIntervalMs = 30_000, limits: overrides = {}, pttProvider = null, pttLimits = {} } = {}) {
  if (!Number.isFinite(ttlMs) || ttlMs <= 0 || !Number.isFinite(cleanupIntervalMs) || cleanupIntervalMs < 0) {
    throw new TypeError('Invalid lifecycle interval');
  }
  const limits = { ...defaults, ...overrides };
  for (const [name, value] of Object.entries(limits)) {
    if (!Object.hasOwn(defaults, name) || !Number.isSafeInteger(value) || value < 1) throw new TypeError(`Invalid limit: ${name}`);
  }
  const rooms = new Map();
  let audioBytes = 0;
  let inflightUploads = 0;
  let stopped = false;
  const now = () => Number(clock());
  const ptt = createPttAdapter({ provider: pttProvider, clock: now, limits: pttLimits,
    isActive: (room, member) => !stopped && rooms.get(room.id) === room &&
      room.members.get(member.id) === member && room.expiresAt > now() });
  const members = (room) => [...room.members.values()].map(({ id, name, role }) => ({ id, name, role }));
  const publicMessage = (message) => ({ id: message.id, sender: message.sender, sequence: message.sequence,
    acceptedAt: message.acceptedAt, autoPlayUntil: message.autoPlayUntil,
    durationMs: message.durationMs, byteLength: message.audio.length, sampleCount: message.sampleCount,
    sampleRate: 16_000, channels: 1, format: 'pcm_s16le' });
  const state = (room, member) => ({
    room: { id: room.id, createdAt: room.createdAt, expiresAt: room.expiresAt },
    self: { id: member.id, name: member.name, role: member.role }, members: members(room),
    destination: room.destination, revision: room.revision, sequence: room.sequence,
    voiceMessages: [...room.messages.values()].map(publicMessage), serverTime: now(),
  });
  const writeStream = (stream, value) => {
    if (stream.res.destroyed || stream.res.writableEnded) return;
    // One bounded event may exceed the socket high-water mark. Allow it to drain;
    // a client still blocked at the next event is disconnected instead of growing a queue.
    if (stream.blocked) { stream.res.destroy(); return; }
    if (!stream.res.write(value)) {
      stream.blocked = true;
      stream.res.once('drain', () => { stream.blocked = false; });
    }
  };
  const send = (stream, event, sequence, data) => {
    writeStream(stream, `id: ${sequence}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };
  const broadcast = (room, event, data) => {
    for (const stream of room.streams) send(stream, event, room.sequence, { ...data, sequence: room.sequence, serverTime: now() });
  };
  function removeRoom(room, reason) {
    if (rooms.get(room.id) !== room) return;
    ptt.removeRoom(room);
    room.sequence += 1;
    broadcast(room, 'closed', { reason });
    for (const stream of room.streams) { clearInterval(stream.heartbeat); stream.res.end(); }
    room.streams.clear();
    for (const message of room.messages.values()) { audioBytes -= message.audio.length; message.audio.fill(0); }
    room.messages.clear();
    for (const member of room.members.values()) { member.sent.clear(); member.token = ''; }
    room.members.clear();
    room.inviteSecret = '';
    rooms.delete(room.id);
  }
  function cleanupExpired() {
    const at = now();
    for (const room of rooms.values()) if (room.expiresAt <= at) removeRoom(room, 'expired');
    ptt.cleanup();
  }
  const ensureActive = (room) => {
    if (rooms.get(room.id) !== room || room.expiresAt <= now()) {
      removeRoom(room, 'expired');
      reject(404, 'room_gone', '공유방이 종료되었거나 만료되었습니다.');
    }
  };
  function authenticate(req, room) {
    ensureActive(room);
    const supplied = bearer(req);
    const member = [...room.members.values()].find((candidate) => same(supplied, candidate.token));
    if (!member) reject(403, 'forbidden', '이 공유방의 참여 권한이 없습니다.');
    return member;
  }
  const hostOnly = (member) => {
    if (member.role !== 'host') reject(403, 'host_required', '대표 차량만 변경할 수 있습니다.');
  };
  function addMember(room, name, role) {
    if (room.members.size >= limits.maxMembers) reject(429, 'member_limit', '공유방 참여자 상한에 도달했습니다.');
    const member = { id: id(), name, role, token: secret(), uploads: [], sent: new Map() };
    room.members.set(member.id, member);
    return member;
  }
  async function readAudio(req) {
    if (inflightUploads >= limits.maxInflightUploads) reject(429, 'upload_concurrency_limit', '동시에 전송하는 원음이 많아요. 잠시 뒤 직접 다시 시도해 주세요.');
    inflightUploads += 1;
    try { return await bodyBytes(req, Math.min(MAX_WAV_BYTES, limits.maxWavBytes)); }
    finally { inflightUploads -= 1; }
  }

  const server = createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Permissions-Policy', 'microphone=(self), camera=(), geolocation=()');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self' blob:; worker-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    const json = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(value));
    };
    try {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) reject(403, 'invalid_host', '로컬 주소로 접속해 주세요.');
      if (!['GET', 'POST', 'DELETE'].includes(req.method)) reject(405, 'method_not_allowed', '지원하지 않는 요청입니다.');
      if (req.method !== 'GET' && ((req.headers.origin && req.headers.origin !== url.origin) ||
          (req.headers['sec-fetch-site'] && !['same-origin', 'none'].includes(req.headers['sec-fetch-site'])))) {
        reject(403, 'cross_origin', '다른 출처의 변경 요청은 허용하지 않습니다.');
      }
      if (req.method === 'GET' && assets.has(url.pathname)) {
        const [name, type] = assets.get(url.pathname);
        const bytes = await readFile(new URL(`./public/${name}`, import.meta.url));
        res.writeHead(200, { 'Content-Type': type });
        return res.end(bytes);
      }
      if (!url.pathname.startsWith('/api/')) reject(404, 'not_found', '페이지를 찾을 수 없습니다.');
      if (url.search) reject(400, 'query_not_supported', '인증과 요청값을 URL에 넣지 마세요.');
      cleanupExpired();
      if (req.method === 'POST' && url.pathname === '/api/rooms') {
        const name = personName(await jsonBody(req));
        cleanupExpired();
        if (stopped) reject(503, 'server_stopping', '서버가 종료 중입니다.');
        if (rooms.size >= limits.maxRooms) reject(429, 'room_limit', '공유방 상한에 도달했습니다.');
        const createdAt = now();
        const room = { id: id(), inviteSecret: secret(), members: new Map(), messages: new Map(),
          streams: new Set(), createdAt, expiresAt: createdAt + ttlMs, destination: null, revision: 0, sequence: 0 };
        const member = addMember(room, name, 'host');
        rooms.set(room.id, room);
        return json(201, { roomId: room.id, memberId: member.id, role: member.role, token: member.token,
          inviteSecret: room.inviteSecret, state: state(room, member) });
      }
      const match = /^\/api\/rooms\/([a-f0-9]{24})(?:\/(members|members\/me|destination|events|voice|voice\/[a-f0-9]{24}\/audio|ptt))?$/.exec(url.pathname);
      if (!match) reject(404, 'not_found', '요청한 경로를 찾을 수 없습니다.');
      const [, roomId, action] = match;
      const room = rooms.get(roomId);
      if (!room) reject(404, 'room_gone', '공유방이 종료되었거나 만료되었습니다.');
      if (req.method === 'POST' && action === 'members') {
        if (!same(bearer(req), room.inviteSecret)) reject(403, 'invalid_invite', '초대 링크를 확인해 주세요.');
        const name = personName(await jsonBody(req));
        ensureActive(room);
        const member = addMember(room, name, 'member');
        room.sequence += 1;
        broadcast(room, 'members', { members: members(room) });
        return json(201, { roomId, memberId: member.id, role: member.role, token: member.token, state: state(room, member) });
      }
      const member = authenticate(req, room);
      if (!action && req.method === 'GET') return json(200, state(room, member));
      if (!action && req.method === 'DELETE') {
        hostOnly(member);
        removeRoom(room, 'closed');
        return json(200, { closed: true });
      }
      if (action === 'members/me' && req.method === 'DELETE') {
        if (member.role === 'host') reject(403, 'host_must_close', '대표 차량은 공유방을 종료해 주세요.');
        ptt.removeMember(room, member);
        for (const stream of room.streams) if (stream.memberId === member.id) {
          send(stream, 'closed', room.sequence, { reason: 'left', serverTime: now() });
          clearInterval(stream.heartbeat);
          stream.res.end();
          room.streams.delete(stream);
        }
        room.members.delete(member.id);
        member.sent.clear();
        member.token = '';
        room.sequence += 1;
        broadcast(room, 'members', { members: members(room) });
        return json(200, { left: true });
      }
      if (action === 'ptt') {
        if (req.method === 'GET') return json(200, ptt.get(room, member));
        const body = await jsonBody(req);
        // Reading the body yields: a concurrent leave/close/expiry must revoke this request too.
        authenticate(req, room);
        const input = pttMutation(body, req.method === 'POST');
        return json(200, req.method === 'POST' ? ptt.register(room, member, input) : ptt.unregister(room, member, input));
      }
      if (action === 'events' && req.method === 'GET') {
        const count = [...room.streams].filter((stream) => stream.memberId === member.id).length;
        if (count >= limits.maxStreamsPerMember) reject(429, 'stream_limit', '이 참여자의 동시 연결 상한에 도달했습니다.');
        res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
        const stream = { res, memberId: member.id, heartbeat: null, blocked: false };
        room.streams.add(stream);
        send(stream, 'snapshot', room.sequence, state(room, member));
        stream.heartbeat = setInterval(() => writeStream(stream, ': connected\n\n'), 15_000).unref();
        res.on('close', () => { clearInterval(stream.heartbeat); room.streams.delete(stream); });
        return;
      }
      if (action === 'destination' && req.method === 'POST') {
        hostOnly(member);
        const body = await jsonBody(req);
        if (!objectWith(body, ['name', 'latitude', 'longitude']) || typeof body.name !== 'string' ||
            !body.name.trim() || body.name.length > 100 || /[\u0000-\u001f\u007f]/u.test(body.name) ||
            typeof body.latitude !== 'number' || !Number.isFinite(body.latitude) || body.latitude < -90 || body.latitude > 90 ||
            typeof body.longitude !== 'number' || !Number.isFinite(body.longitude) || body.longitude < -180 || body.longitude > 180) {
          reject(400, 'invalid_destination', '장소명과 올바른 위도·경도를 입력해 주세요.');
        }
        authenticate(req, room);
        room.revision += 1;
        room.sequence += 1;
        room.destination = { name: body.name.trim(), latitude: body.latitude, longitude: body.longitude,
          changedAt: now(), changedBy: member.id };
        const update = { destination: room.destination, revision: room.revision, sequence: room.sequence, serverTime: now() };
        broadcast(room, 'destination', update);
        return json(200, update);
      }
      if (action === 'voice' && req.method === 'POST') {
        const clientMessageId = req.headers['x-client-message-id'];
        if (typeof clientMessageId !== 'string' || !/^[A-Za-z0-9_-]{16,80}$/.test(clientMessageId)) {
          reject(400, 'invalid_message_id', '고유한 X-Client-Message-Id가 필요합니다.');
        }
        if (!/^audio\/wav(?:\s*;.*)?$/i.test(req.headers['content-type'] ?? '')) reject(415, 'wav_required', 'audio/wav 바이트가 필요합니다.');
        const audio = await readAudio(req);
        authenticate(req, room);
        const hash = createHash('sha256').update(audio).digest('hex');
        const existing = member.sent.get(clientMessageId);
        if (existing) {
          if (hash !== existing.hash) reject(409, 'message_id_conflict', '같은 전송 ID에 다른 원음이 있습니다.');
          return json(200, { message: publicMessage(existing), duplicate: true, serverTime: now() });
        }
        let format;
        try { format = inspectWav(audio); }
        catch (error) { reject(422, 'invalid_wav', error.message); }
        const acceptedAt = now();
        member.uploads = member.uploads.filter((at) => at > acceptedAt - 60_000);
        if (member.uploads.length >= limits.uploadsPerMinute) {
          res.setHeader('Retry-After', String(Math.max(1, Math.ceil((member.uploads[0] + 60_000 - acceptedAt) / 1000))));
          reject(429, 'voice_rate_limit', `원음은 참여자마다 1분에 ${limits.uploadsPerMinute}개까지 보낼 수 있습니다.`);
        }
        if (room.messages.size >= limits.maxMessagesPerRoom) reject(507, 'history_limit', '공유방 원음 보관 상한에 도달했습니다. 새 공유방을 만들어 주세요.');
        if (audioBytes + audio.length > limits.maxAudioBytes) reject(507, 'audio_storage_limit', '원음 메모리 보관 상한에 도달했습니다.');
        room.sequence += 1;
        const message = { id: id(), sender: { id: member.id, name: member.name }, sequence: room.sequence,
          acceptedAt, autoPlayUntil: acceptedAt + 30_000, ...format, audio, hash };
        room.messages.set(message.id, message);
        member.sent.set(clientMessageId, message);
        member.uploads.push(acceptedAt);
        audioBytes += audio.length;
        broadcast(room, 'voice', { message: publicMessage(message) });
        ptt.notify(room, member, publicMessage(message));
        return json(201, { message: publicMessage(message), duplicate: false, serverTime: now() });
      }
      if (action?.startsWith('voice/') && req.method === 'GET') {
        const messageId = action.split('/')[1];
        const message = room.messages.get(messageId);
        if (!message) reject(404, 'voice_not_found', '원음을 찾을 수 없습니다.');
        res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': message.audio.length,
          'Content-Disposition': 'attachment; filename="voice.wav"' });
        return res.end(message.audio);
      }
      reject(405, 'method_not_allowed', '지원하지 않는 요청입니다.');
    } catch (error) {
      if (res.destroyed || res.writableEnded) return;
      req.resume();
      const known = error instanceof ApiError || error instanceof PttError;
      if (!res.headersSent) json(known ? error.status : 500,
        { code: known ? error.code : 'internal_error',
          error: known ? error.message : '요청을 처리하지 못했습니다.',
          ...(error instanceof PttError && error.state ? error.state : {}) });
      else res.end();
    }
  });
  server.maxHeadersCount = 64;
  server.headersTimeout = 10_000;
  server.requestTimeout = 15_000;
  server.keepAliveTimeout = 5_000;
  const sweep = cleanupIntervalMs > 0 ? setInterval(cleanupExpired, cleanupIntervalMs).unref() : null;
  server.on('close', () => {
    stopped = true;
    clearInterval(sweep);
    for (const room of [...rooms.values()]) removeRoom(room, 'server_stopped');
    ptt.close().catch(() => {});
  });
  return {
    server, cleanupExpired, pttStats: ptt.stats,
    stats: () => ({ rooms: rooms.size, members: [...rooms.values()].reduce((sum, room) => sum + room.members.size, 0),
      messages: [...rooms.values()].reduce((sum, room) => sum + room.messages.size, 0),
      streams: [...rooms.values()].reduce((sum, room) => sum + room.streams.size, 0), audioBytes }),
    close: async () => {
      stopped = true;
      clearInterval(sweep);
      for (const room of [...rooms.values()]) removeRoom(room, 'server_stopped');
      await ptt.close();
      return new Promise((fulfill, fail) => {
        if (!server.listening) return fulfill();
        server.close((error) => error ? fail(error) : fulfill());
        server.closeAllConnections();
      });
    },
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const port = Number(process.env.PORT ?? 4180);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be between 1 and 65535');
  const pttProvider = await loadApnsProviderFromEnvironment();
  const app = createPrototypeServer({ pttProvider });
  app.server.listen(port, '127.0.0.1', () => console.log(`함께 가는 길: http://127.0.0.1:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { app.close().catch(() => { process.exitCode = 1; }); });
}
