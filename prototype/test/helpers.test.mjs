import test from 'node:test';
import assert from 'node:assert/strict';
import { AutoPlayQueue, SSEParser, ServerClock, encodeWavPCM16, resampleMono, parseInvitation, naverNavigationUrl } from '../public/helpers.js';
import { inspectWav } from '../wav.mjs';

const voice = (id, sequence, acceptedAt = 1000, sender = 'other') => ({ id, sequence, acceptedAt, autoPlayUntil: acceptedAt + 30000, sender: { id: sender } });

test('queue requires opt-in, ignores prior history and own voice, preserves order, and deduplicates reconnect snapshots', () => {
  let now = 2000; const queue = new AutoPlayQueue({ selfId: 'self', now: () => now });
  queue.offer([voice('old-before-opt-in', 1)]); assert.equal(queue.take(), null);
  queue.setEnabled(true);
  queue.offer([voice('three', 3), voice('two', 2), voice('mine', 4, 1000, 'self'), voice('old-before-opt-in', 1)]);
  assert.equal(queue.take().id, 'two'); assert.equal(queue.take().id, 'three'); assert.equal(queue.take(), null);
  queue.offer([voice('three', 3), voice('two', 2)]); assert.equal(queue.take(), null);
  queue.offer([voice('expires-in-queue', 5)]); now = 31000; assert.equal(queue.take(), null);
  queue.offer([voice('already-stale', 6, 0), voice('future', 7, 40000)]); assert.equal(queue.take(), null);
});

test('turning auto-play off clears every pending message; explicit re-enable waits for new voice', () => {
  const queue = new AutoPlayQueue({ selfId: 'self', now: () => 5000 }); queue.setEnabled(true);
  queue.offer([voice('one', 1), voice('two', 2)]); queue.setEnabled(false);
  assert.equal(queue.pending.length, 0); queue.setEnabled(true); queue.offer([voice('one', 1), voice('two', 2)]);
  assert.equal(queue.take(), null); queue.offer([voice('new', 3)]); assert.equal(queue.take().id, 'new');
  const liedAboutWindow = { ...voice('unbounded', 4), autoPlayUntil: 1000000 }; queue.offer([liedAboutWindow]); assert.equal(queue.take(), null);
});

test('SSE parser handles network chunk boundaries, Korean UTF-8, CRLF, multiline data, comments and persistent event ids', () => {
  const received = []; const parser = new SSEParser((event) => received.push(event));
  const bytes = new TextEncoder().encode(': hello\r\nid: 12\r\nevent: voice\r\ndata: {"name":\r\ndata: "가상 동행"}\r\n\r\ndata: next\n\n');
  const decoder = new TextDecoder();
  for (const byte of bytes) parser.feed(decoder.decode(new Uint8Array([byte]), { stream: true }));
  assert.equal(received.length, 2); assert.deepEqual(received[0], { id: '12', event: 'voice', data: '{"name":\n"가상 동행"}' });
  assert.equal(JSON.parse(received[0].data).name, '가상 동행'); assert.deepEqual(received[1], { id: '12', event: 'message', data: 'next' });
  parser.feed('data: incomplete'); assert.equal(received.length, 2);
});

test('SSE framing refuses unbounded lines and unbounded multiline events', () => {
  assert.throws(() => new SSEParser(() => {}, 20).feed('data: ' + 'x'.repeat(21)));
  const parser = new SSEParser(() => {}, 20); parser.feed('data: 123456789\n'); assert.throws(() => parser.feed('data: 123456789\n'));
});

test('server clock uses request RTT conservatively and monotonic elapsed time rather than wall-clock changes', () => {
  let tick = 10; const clock = new ServerClock(() => tick);
  clock.update(1000, 200); assert.equal(clock.now(), 1200); tick += 100; assert.equal(clock.now(), 1300);
  clock.update(1050); assert.equal(clock.now(), 1300); tick += 50; assert.equal(clock.now(), 1350);
  clock.update(2000); assert.equal(clock.now(), 2000);
});

test('browser resampling/encoding makes actual PCM16 mono16k WAV and clamps samples safely', () => {
  const input = new Float32Array(48000).fill(.25); const mono = resampleMono(input, 48000);
  assert.equal(mono.length, 16000); assert(mono.every((sample) => sample === .25));
  mono.set([-2, 2, NaN, 0]); const bytes = Buffer.from(encodeWavPCM16(mono));
  assert.equal(inspectWav(bytes).durationMs, 1000); assert.equal(bytes.readInt16LE(44), -32768); assert.equal(bytes.readInt16LE(46), 32767);
  assert.equal(bytes.readInt16LE(48), 0); assert.equal(bytes.readInt16LE(50), 0);
  assert.equal(resampleMono(new Float32Array(44100 * 16), 44100).length, 240000);
  assert.equal(resampleMono(new Float32Array(8000).fill(.5), 8000).length, 16000);
  assert.throws(() => encodeWavPCM16(new Float32Array(15999))); assert.throws(() => encodeWavPCM16(new Float32Array(240001)));
  assert.throws(() => resampleMono(input, 0));
});

test('invitation parser accepts only this origin fragment invite, never query access credentials', () => {
  const origin = 'http://127.0.0.1:4180'; const roomId = 'ab'.repeat(12); const inviteSecret = 'a'.repeat(43);
  assert.deepEqual(parseInvitation(`${origin}/#join=${roomId}.${inviteSecret}`, origin), { roomId, inviteSecret });
  assert.deepEqual(parseInvitation(`#join=${roomId}.${inviteSecret}`, origin), { roomId, inviteSecret });
  for (const input of [`https://other.invalid/#join=${roomId}.${inviteSecret}`, `${origin}/?token=${inviteSecret}`, `${origin}/#host=${roomId}.${inviteSecret}`, 'not a link']) {
    assert.equal(parseInvitation(input, origin), null);
  }
});

test('NAVER handoff encodes destination/app URL once and respects documented coordinate range', () => {
  const destination = { name: '가상 공원 & #1 + 쉼터', latitude: 37.52, longitude: 127.12 };
  const link = new URL(naverNavigationUrl(destination, 'http://127.0.0.1:4180'));
  assert.equal(link.protocol, 'nmap:'); assert.equal(link.hostname, 'navigation');
  assert.equal(link.searchParams.get('dname'), destination.name); assert.equal(link.searchParams.get('appname'), 'http://127.0.0.1:4180');
  assert.equal(link.searchParams.get('dlat'), '37.52'); assert.equal(link.searchParams.size, 4);
  assert.equal(naverNavigationUrl({ ...destination, latitude: 0 }, 'http://127.0.0.1:4180'), null);
});
