import { encodeWavPCM16, resampleMono } from './helpers.js';

const audioContextType = () => globalThis.AudioContext ?? globalThis.webkitAudioContext;
const cancelled = () => new DOMException('작업을 취소했습니다.', 'AbortError');

export function microphoneError(error) {
  if (error?.name === 'NotAllowedError' || error?.name === 'SecurityError') return '마이크 권한이 거절됐어요. 브라우저 사이트 설정에서 허용한 뒤 다시 눌러 주세요.';
  if (error?.name === 'NotFoundError') return '사용할 마이크가 없어요. 마이크 연결을 확인해 주세요.';
  if (error?.name === 'NotReadableError') return '마이크를 열 수 없어요. 다른 앱의 마이크 사용을 확인해 주세요.';
  if (error?.name === 'AbortError') return '녹음을 취소했어요.';
  return error?.message || '녹음하지 못했어요. 브라우저와 마이크를 확인해 주세요.';
}

/** A single foreground capture, cancelled safely even if a permission prompt is still pending. */
export class MicRecorder {
  constructor({ onLimit }) {
    this.onLimit = onLimit;
    this.aborted = false;
    this.stopping = false;
    this.chunks = [];
    this.frames = 0;
    this.startedAt = null;
    this.finished = null;
  }
  async start() {
    if (!globalThis.isSecureContext) throw new Error('마이크는 localhost 또는 신뢰된 HTTPS에서 사용할 수 있어요. 일반 LAN HTTP 주소에서는 녹음할 수 없어요.');
    const AudioContext = audioContextType();
    if (!navigator.mediaDevices?.getUserMedia || !AudioContext || !globalThis.AudioWorkletNode) {
      throw new Error('이 브라우저는 원음 녹음을 지원하지 않아요. 최신 Safari 또는 Chrome의 열린 화면에서 확인해 주세요.');
    }
    try {
      this.context = new AudioContext();
      if (!this.context.audioWorklet || this.context.sampleRate < 8000 || this.context.sampleRate > 192000) {
        throw new Error('이 브라우저의 마이크 녹음 형식을 지원하지 않아요.');
      }
      let resumeError;
      const resumed = this.context.resume().catch((error) => { resumeError = error; });
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false });
      this.stream = stream;
      if (this.aborted || document.hidden) throw cancelled();
      await resumed;
      if (resumeError) throw resumeError;
      if (this.context.state !== 'running') throw new Error('녹음 오디오를 시작하지 못했어요. 다시 눌러 주세요.');
      await this.context.audioWorklet.addModule('/recording-worklet.js');
      if (this.aborted || document.hidden) throw cancelled();
      this.source = this.context.createMediaStreamSource(stream);
      this.node = new AudioWorkletNode(this.context, 'voice-capture', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
      this.maxFrames = Math.floor(this.context.sampleRate * 15);
      this.finished = new Promise((fulfill) => { this.resolveFinished = fulfill; });
      this.node.port.onmessage = ({ data }) => {
        if (data?.type === 'samples' && !this.aborted && data.samples instanceof Float32Array) {
          const count = Math.min(data.samples.length, this.maxFrames - this.frames);
          if (count > 0 && data.samples.length <= 2048) { this.chunks.push(data.samples.slice(0, count)); this.frames += count; }
        }
        if (data?.type === 'stopped' || data?.type === 'limit') this.resolveFinished?.();
        if (data?.type === 'limit' && !this.aborted && !this.stopping) this.onLimit();
      };
      this.source.connect(this.node);
      this.node.connect(this.context.destination); // The worklet outputs silence, never microphone feedback.
      this.startedAt = performance.now();
      this.timer = setTimeout(() => { if (!this.aborted && !this.stopping) this.onLimit(); }, 15_000);
      this.context.onstatechange = () => {
        if (!this.stopping && !this.aborted && this.context.state !== 'running') this.onLimit();
      };
    } catch (error) {
      this.aborted = true;
      await this.release();
      throw error;
    }
  }
  async stop() {
    if (this.aborted) throw cancelled();
    if (this.stopping) throw new Error('이미 녹음을 마무리하고 있어요.');
    this.stopping = true;
    clearTimeout(this.timer);
    this.node?.port.postMessage({ type: 'stop' });
    for (const track of this.stream?.getTracks() ?? []) track.stop();
    this.stream = null;
    let fallback;
    if (this.finished) await Promise.race([this.finished, new Promise((fulfill) => { fallback = setTimeout(fulfill, 500); })]);
    clearTimeout(fallback);
    const inputRate = this.context?.sampleRate;
    const input = new Float32Array(this.frames);
    let offset = 0;
    for (const chunk of this.chunks) { input.set(chunk, offset); offset += chunk.length; }
    await this.release();
    if (this.aborted) throw cancelled();
    const samples = resampleMono(input, inputRate);
    const bytes = encodeWavPCM16(samples);
    return { bytes, durationMs: samples.length / 16, capturedAt: Date.now() };
  }
  async cancel() { this.aborted = true; this.stopping = true; this.resolveFinished?.(); await this.release(); }
  async release() {
    clearTimeout(this.timer);
    for (const track of this.stream?.getTracks() ?? []) track.stop();
    this.stream = null;
    if (this.node) { this.node.port.onmessage = null; this.node.port.close(); this.node.disconnect(); }
    this.source?.disconnect();
    if (this.context && this.context.state !== 'closed') {
      this.context.onstatechange = null;
      await this.context.close().catch(() => {});
    }
    this.chunks = [];
  }
}

/** One authenticated playback at a time. Unlock must be called directly by a user gesture. */
export class VoicePlayer {
  constructor({ onInterrupted = () => {} } = {}) { this.generation = 0; this.context = null; this.source = null; this.pending = null; this.onInterrupted = onInterrupted; }
  async unlock() {
    const AudioContext = audioContextType();
    if (!AudioContext) throw new Error('이 브라우저는 원음 재생을 지원하지 않아요.');
    if (!this.context || this.context.state === 'closed') {
      this.context = new AudioContext();
      this.context.onstatechange = () => {
        if (this.context.state !== 'running' && (this.source || this.pending)) { this.stop(); this.onInterrupted(); }
      };
    }
    try { await this.context.resume(); }
    catch { throw new Error('오디오 재생이 거절됐어요. 브라우저 소리 설정을 확인하고 다시 눌러 주세요.'); }
    if (this.context.state !== 'running') throw new Error('오디오 재생이 허용되지 않았어요. 화면에서 재생을 다시 눌러 주세요.');
  }
  stop() {
    this.generation += 1;
    this.pending?.abort();
    this.pending = null;
    this.finishPlayback?.(false);
    this.finishPlayback = null;
    if (this.source) { this.source.onended = null; try { this.source.stop(); } catch {} this.source.disconnect(); this.source = null; }
  }
  async play({ download, canStart = () => true, onStart = () => {} }) {
    this.stop();
    const generation = this.generation;
    const abort = new AbortController();
    this.pending = abort;
    const check = () => {
      if (generation !== this.generation || abort.signal.aborted || document.hidden) throw cancelled();
      if (!canStart()) { const error = new Error('자동 재생 유효시간 30초가 지났어요. 목록에서 직접 재생해 주세요.'); error.name = 'StaleAudioError'; throw error; }
    };
    try {
      check();
      if (!this.context || this.context.state !== 'running') throw new Error('재생이 일시정지됐어요. 화면에서 재생을 다시 눌러 주세요.');
      const bytes = await download(abort.signal);
      check();
      const buffer = await this.context.decodeAudioData(bytes);
      check();
      if (this.context.state !== 'running') throw new Error('재생이 일시정지됐어요. 화면에서 재생을 다시 눌러 주세요.');
      const source = this.context.createBufferSource();
      this.source = source;
      source.buffer = buffer;
      source.connect(this.context.destination);
      return await new Promise((fulfill, fail) => {
        this.finishPlayback = fulfill;
        source.onended = () => {
          if (generation !== this.generation) return;
          this.source = null;
          this.finishPlayback = null;
          source.disconnect();
          fulfill(true);
        };
        try { source.start(); onStart(); }
        catch (error) { source.disconnect(); this.source = null; this.finishPlayback = null; fail(error); }
      });
    } finally {
      if (generation === this.generation) this.pending = null;
    }
  }
  async close() { this.stop(); if (this.context && this.context.state !== 'closed') { this.context.onstatechange = null; await this.context.close().catch(() => {}); } this.context = null; }
}
