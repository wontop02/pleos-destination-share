import { once } from 'node:events';
import { createPrototypeServer } from '../server.mjs';
import { SSEParser } from '../public/helpers.js';

// Fictional generated PCM samples, never a recording of a real microphone or person.
export function fictionalWav(sampleCount = 16_000, frequency = 220) {
  const bytes = Buffer.alloc(44 + sampleCount * 2);
  bytes.write('RIFF', 0); bytes.writeUInt32LE(bytes.length - 8, 4); bytes.write('WAVEfmt ', 8);
  bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(16000, 24); bytes.writeUInt32LE(32000, 28);
  bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34); bytes.write('data', 36); bytes.writeUInt32LE(sampleCount * 2, 40);
  for (let sample = 0; sample < sampleCount; sample += 1) bytes.writeInt16LE(Math.round(Math.sin(2 * Math.PI * frequency * sample / 16000) * 4000), 44 + sample * 2);
  return bytes;
}
export const messageId = (index) => `fictional-message-${String(index).padStart(8, '0')}`;

export async function runningApp(t, options = {}) {
  const app = createPrototypeServer({ cleanupIntervalMs: 0, ...options });
  app.server.listen(0, '127.0.0.1');
  await once(app.server, 'listening');
  const base = `http://127.0.0.1:${app.server.address().port}`;
  t.after(() => app.close());
  const call = async (method, path, { token, body, headers = {} } = {}) => {
    const response = await fetch(`${base}${path}`, { method, headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined || Buffer.isBuffer(body) ? {} : { 'Content-Type': 'application/json' }), ...headers,
    }, body: body === undefined ? undefined : Buffer.isBuffer(body) ? body : JSON.stringify(body) });
    const data = response.headers.get('content-type')?.startsWith('audio/wav') ? Buffer.from(await response.arrayBuffer()) : await response.json();
    return { response, status: response.status, data };
  };
  const create = async (name = '대표 가상 참여자') => (await call('POST', '/api/rooms', { body: { name } })).data;
  const join = async (host, name = '동행 가상 참여자') => (await call('POST', `/api/rooms/${host.roomId}/members`, { token: host.inviteSecret, body: { name } })).data;
  const upload = (member, index, bytes = fictionalWav()) => call('POST', `/api/rooms/${member.roomId}/voice`, {
    token: member.token, body: bytes, headers: { 'Content-Type': 'audio/wav', 'X-Client-Message-Id': messageId(index) },
  });
  return { ...app, base, call, create, join, upload };
}

export async function events(t, app, member) {
  const abort = new AbortController();
  const response = await fetch(`${app.base}/api/rooms/${member.roomId}/events`, { headers: { Authorization: `Bearer ${member.token}` }, signal: abort.signal });
  if (!response.ok) throw new Error(`Unexpected SSE status ${response.status}`);
  const reader = response.body.getReader();
  const history = [];
  const pending = [];
  const waiters = new Set();
  let ended = false;
  const notify = () => { for (const waiter of [...waiters]) waiter(); };
  const parser = new SSEParser((event) => { const parsed = { ...event, data: JSON.parse(event.data) }; history.push(parsed); pending.push(parsed); notify(); });
  const pump = (async () => {
    const decoder = new TextDecoder();
    try {
      while (true) { const chunk = await reader.read(); if (chunk.done) break; parser.feed(decoder.decode(chunk.value, { stream: true })); }
    } catch (error) { if (!abort.signal.aborted) throw error; }
    finally { ended = true; notify(); }
  })();
  const close = async () => { abort.abort(); await reader.cancel().catch(() => {}); await pump; };
  t.after(close);
  const next = (type) => new Promise((fulfill, fail) => {
    let timer;
    const check = () => {
      const index = pending.findIndex((event) => event.event === type);
      if (index < 0 && !ended) return;
      clearTimeout(timer); waiters.delete(check);
      if (index < 0) fail(new Error(`SSE ended before ${type}`)); else fulfill(pending.splice(index, 1)[0]);
    };
    timer = setTimeout(() => { waiters.delete(check); fail(new Error(`SSE timeout: ${type}`)); }, 2000);
    waiters.add(check); check();
  });
  return { next, history, close, ended: () => ended };
}
