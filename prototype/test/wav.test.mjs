import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectWav } from '../wav.mjs';
import { fictionalWav, runningApp } from './support.mjs';

function change(bytes, edit) { const result = Buffer.from(bytes); edit(result); return result; }
function appendChunk(bytes, kind, data, padding = true) {
  const header = Buffer.alloc(8); header.write(kind); header.writeUInt32LE(data.length, 4);
  const result = Buffer.concat([bytes, header, data, ...(padding && data.length % 2 ? [Buffer.alloc(1)] : [])]);
  result.writeUInt32LE(result.length - 8, 4); return result;
}
function prependJunk(bytes, size) {
  const chunk = Buffer.alloc(8 + size + size % 2); chunk.write('JUNK'); chunk.writeUInt32LE(size, 4);
  const result = Buffer.concat([bytes.subarray(0, 12), chunk, bytes.subarray(12)]); result.writeUInt32LE(result.length - 8, 4); return result;
}
const good = fictionalWav();
const malformed = [
  ['empty', Buffer.alloc(0)], ['text masquerading as audio', Buffer.from('audio/wav 1500ms')],
  ['truncated header', good.subarray(0, 43)],
  ['RIFF size shorter than bytes', change(good, (b) => b.writeUInt32LE(b.length - 9, 4))],
  ['RIFF size longer than bytes', change(good, (b) => b.writeUInt32LE(b.length - 7, 4))],
  ['big-endian RIFX', change(good, (b) => b.write('RIFX', 0))],
  ['high-bit RIFF bytes', change(good, (b) => { b[0] |= 128; })],
  ['invalid WAVE signature', change(good, (b) => b.write('MP3 ', 8))],
  ['floating-point codec', change(good, (b) => b.writeUInt16LE(3, 20))],
  ['stereo', change(good, (b) => b.writeUInt16LE(2, 22))],
  ['48000Hz', change(good, (b) => b.writeUInt32LE(48000, 24))],
  ['inconsistent byteRate', change(good, (b) => b.writeUInt32LE(16000, 28))],
  ['inconsistent blockAlign', change(good, (b) => b.writeUInt16LE(4, 32))],
  ['8-bit samples', change(good, (b) => b.writeUInt16LE(8, 34))],
  ['missing fmt chunk', change(good, (b) => b.write('JUNK', 12))],
  ['high-bit fmt chunk', change(good, (b) => { b[12] |= 128; })],
  ['missing data chunk', change(good, (b) => b.write('JUNK', 36))],
  ['chunk claims bytes outside container', change(good, (b) => b.writeUInt32LE(0xffffffff, 40))],
  ['fmt size outside allowed PCM structure', change(good, (b) => b.writeUInt32LE(12, 16))],
  ['duplicate fmt after data', appendChunk(good, 'fmt ', good.subarray(20, 36))],
  ['duplicate data', appendChunk(good, 'data', Buffer.alloc(2))],
  ['odd sample byte length', change(good, (b) => b.writeUInt32LE(31999, 40))],
  ['less than one second', fictionalWav(15_999)],
  ['more than fifteen seconds', fictionalWav(240_001)],
  ['unframed trailing bytes', change(Buffer.concat([good, Buffer.from('bad')]), (b) => b.writeUInt32LE(b.length - 8, 4))],
  ['excess metadata outside data chunk', prependJunk(good, 4096)],
  ['odd unknown chunk lacks required padding', appendChunk(good, 'JUNK', Buffer.alloc(1), false)],
  ['data before format', (() => { const b = Buffer.concat([good.subarray(0, 12), good.subarray(36), good.subarray(12, 36)]); return b; })()],
];

test('WAV validator accepts exact one/fifteen-second boundaries and bounded unknown chunks', () => {
  assert.equal(inspectWav(good).sampleCount, 16000);
  assert.equal(inspectWav(fictionalWav(240_000)).durationMs, 15000);
  const extended = prependJunk(good, 3);
  assert.equal(inspectWav(extended).durationMs, 1000);
  const fmt18 = Buffer.concat([good.subarray(0, 36), Buffer.alloc(2), good.subarray(36)]);
  fmt18.writeUInt32LE(fmt18.length - 8, 4); fmt18.writeUInt32LE(18, 16);
  assert.equal(inspectWav(fmt18).durationMs, 1000);
  fmt18.writeUInt16LE(2, 36); assert.throws(() => inspectWav(fmt18));
});

test('WAV validation rejects malformed containers and format/duration lies through actual HTTP', async (t) => {
  const app = await runningApp(t); const host = await app.create();
  for (const [index, [name, bytes]] of malformed.entries()) {
    assert.throws(() => inspectWav(bytes), undefined, name);
    const response = await app.upload(host, index, bytes);
    assert.equal(response.status, 422, name); assert.equal(response.data.code, 'invalid_wav', name);
  }
  assert.equal(app.stats().messages, 0); assert.equal(app.stats().audioBytes, 0);
  assert.equal((await app.upload(host, 100, good)).status, 201, 'rejected inputs did not consume sender quota');
});
