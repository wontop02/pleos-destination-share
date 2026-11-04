import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { MicRecorder, VoicePlayer } from '../public/audio.js';
import { inspectWav } from '../wav.mjs';

const deferred = () => { let resolve; let reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

function browserEnvironment(t, { getUserMedia, secure = true, decode, autoEnd = true } = {}) {
  const contexts = []; const nodes = [];
  const track = { stops: 0, stop() { this.stops += 1; } };
  const stream = { getTracks: () => [track] };
  class AudioContext {
    constructor() {
      this.state = 'suspended'; this.sampleRate = 48000; this.destination = {}; this.starts = 0; this.closes = 0;
      this.audioWorklet = { addModule: async () => {} }; contexts.push(this);
    }
    async resume() { this.state = 'running'; this.onstatechange?.(); }
    async close() { this.closes += 1; this.state = 'closed'; this.onstatechange?.(); }
    createMediaStreamSource() { return { connect() {}, disconnect() {} }; }
    async decodeAudioData(bytes) { return decode ? decode(bytes) : { duration: 1 }; }
    createBufferSource() {
      const source = { connect() {}, disconnect() {}, stop() {}, start: () => { this.starts += 1; if (autoEnd) queueMicrotask(() => source.onended?.()); } };
      return source;
    }
  }
  class AudioWorkletNode {
    constructor() {
      this.port = { onmessage: null, closed: false, close() { this.closed = true; },
        postMessage(data) { if (data.type === 'stop') queueMicrotask(() => this.onmessage?.({ data: { type: 'stopped' } })); } };
      nodes.push(this);
    }
    connect() {} disconnect() {}
  }
  const values = { isSecureContext: secure, document: { hidden: false }, navigator: { mediaDevices: { getUserMedia: getUserMedia ?? (async () => stream) } }, AudioContext, AudioWorkletNode };
  const original = new Map(Object.keys(values).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(values)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  t.after(() => { for (const [key, descriptor] of original) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; } });
  return { contexts, nodes, track, stream };
}

async function worklet() {
  const messages = []; let Processor;
  class Base { constructor() { this.port = { postMessage: (data) => messages.push(data), onmessage: null }; } }
  const context = vm.createContext({ AudioWorkletProcessor: Base, sampleRate: 48000, Float32Array,
    registerProcessor: (name, type) => { assert.equal(name, 'voice-capture'); Processor = type; } });
  vm.runInContext(await readFile(new URL('../public/recording-worklet.js', import.meta.url), 'utf8'), context);
  return { instance: new Processor(), messages };
}

test('actual worklet code bounds chunks and fifteen seconds, averages input channels and outputs silence', async () => {
  const { instance, messages } = await worklet();
  const input = [new Float32Array(128).fill(.5), new Float32Array(128).fill(-.25)];
  const output = [new Float32Array(128).fill(1)];
  let active = true; let blocks = 0;
  while (active && blocks < 6000) { active = instance.process([input], [output]); blocks += 1; }
  const chunks = messages.filter((message) => message.type === 'samples');
  assert.equal(chunks.reduce((sum, message) => sum + message.samples.length, 0), 48000 * 15);
  assert(chunks.every(({ samples }) => samples.length <= 2048 && samples.every((sample) => sample === .125)));
  assert(output[0].every((sample) => sample === 0)); assert.equal(messages.filter((message) => message.type === 'limit').length, 1);
  const count = messages.length; assert.equal(instance.process([input], [output]), false); assert.equal(messages.length, count);
});

test('worklet stop flushes the last partial chunk and stops capturing', async () => {
  const { instance, messages } = await worklet();
  instance.process([[new Float32Array(9).fill(.3)]], [[new Float32Array(9)]]);
  instance.port.onmessage({ data: { type: 'stop' } });
  assert.equal(messages[0].type, 'samples'); assert.equal(messages[0].samples.length, 9); assert.equal(messages[1].type, 'stopped');
  assert.equal(instance.process([[new Float32Array(9)]], [[new Float32Array(9)]]), false);
});

test('cancelling a pending microphone permission releases a late stream and never creates a capture node', async (t) => {
  const permission = deferred(); const env = browserEnvironment(t, { getUserMedia: () => permission.promise });
  const recorder = new MicRecorder({ onLimit() {} }); const starting = recorder.start();
  await recorder.cancel(); permission.resolve(env.stream);
  await assert.rejects(starting, { name: 'AbortError' }); assert.equal(env.track.stops, 1); assert.equal(env.nodes.length, 0);
  assert.equal(env.contexts[0].state, 'closed'); assert.equal(recorder.frames, 0);
});

test('microphone denial and insecure context fail clearly and leave no live capture', async (t) => {
  const denied = new DOMException('denied', 'NotAllowedError');
  const env = browserEnvironment(t, { getUserMedia: async () => { throw denied; } });
  const recorder = new MicRecorder({ onLimit() {} }); await assert.rejects(recorder.start(), { name: 'NotAllowedError' });
  assert.equal(env.contexts[0].state, 'closed'); assert.equal(env.nodes.length, 0);
  globalThis.isSecureContext = false;
  await assert.rejects(new MicRecorder({ onLimit() {} }).start(), /localhost.*HTTPS/);
});

test('recorder converts supplied synthetic worklet samples into valid WAV and releases tracks/context/port', async (t) => {
  const env = browserEnvironment(t); const recorder = new MicRecorder({ onLimit() {} }); t.after(() => recorder.cancel());
  await recorder.start();
  for (let cursor = 0; cursor < 48000; cursor += 2048) env.nodes[0].port.onmessage({ data: { type: 'samples', samples: new Float32Array(Math.min(2048, 48000 - cursor)).fill(.1) } });
  const result = await recorder.stop();
  assert.equal(inspectWav(Buffer.from(result.bytes)).durationMs, 1000); assert.equal(result.durationMs, 1000);
  assert.equal(env.track.stops, 1); assert.equal(env.contexts[0].state, 'closed'); assert.equal(env.nodes[0].port.closed, true); assert.equal(recorder.chunks.length, 0);
});

test('sub-second capture is discarded after releasing all resources', async (t) => {
  const env = browserEnvironment(t); const recorder = new MicRecorder({ onLimit() {} }); t.after(() => recorder.cancel());
  await recorder.start(); env.nodes[0].port.onmessage({ data: { type: 'samples', samples: new Float32Array(2048) } });
  await assert.rejects(recorder.stop(), /1–15초/); assert.equal(env.track.stops, 1); assert.equal(env.contexts[0].state, 'closed');
});

test('player resolves only after ended; stop aborts a pending authenticated download', async (t) => {
  const env = browserEnvironment(t); const player = new VoicePlayer(); t.after(() => player.close()); await player.unlock();
  let starts = 0;
  assert.equal(await player.play({ download: async () => new ArrayBuffer(44), onStart: () => { starts += 1; } }), true);
  assert.equal(starts, 1); assert.equal(env.contexts[0].starts, 1);
  let signal;
  const pending = player.play({ download: (supplied) => { signal = supplied; return new Promise((yes, no) => supplied.addEventListener('abort', () => no(new DOMException('cancelled', 'AbortError')), { once: true })); } });
  player.stop(); await assert.rejects(pending, { name: 'AbortError' }); assert.equal(signal.aborted, true); assert.equal(env.contexts[0].starts, 1);
});

test('player rechecks freshness after downloading and discards a decoded clip after stop', async (t) => {
  const decoding = deferred(); const env = browserEnvironment(t, { decode: () => decoding.promise }); const player = new VoicePlayer(); t.after(() => player.close()); await player.unlock();
  let fresh = true;
  await assert.rejects(player.play({ download: async () => { fresh = false; return new ArrayBuffer(44); }, canStart: () => fresh }), { name: 'StaleAudioError' });
  const pending = player.play({ download: async () => new ArrayBuffer(44) });
  await new Promise((fulfill) => setImmediate(fulfill)); player.stop(); decoding.resolve({ duration: 1 });
  await assert.rejects(pending, { name: 'AbortError' }); assert.equal(env.contexts[0].starts, 0);
});

test('browser audio interruption stops an active source and reports interruption rather than playback success', async (t) => {
  const env = browserEnvironment(t, { autoEnd: false }); let interrupted = 0;
  const player = new VoicePlayer({ onInterrupted: () => { interrupted += 1; } }); t.after(() => player.close()); await player.unlock();
  const started = deferred(); const pending = player.play({ download: async () => new ArrayBuffer(44), onStart: started.resolve }); await started.promise;
  env.contexts[0].state = 'suspended'; env.contexts[0].onstatechange();
  assert.equal(await pending, false); assert.equal(interrupted, 1); assert.equal(player.source, null);
});
