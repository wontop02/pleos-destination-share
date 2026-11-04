export const TARGET_SAMPLE_RATE = 16_000;
export const MAX_RECORDING_SECONDS = 15;

/** Bounded recording data is converted once after capture; no MediaRecorder codec assumptions. */
export function resampleMono(samples, inputRate, outputRate = TARGET_SAMPLE_RATE) {
  if (!(samples instanceof Float32Array) || !Number.isFinite(inputRate) || inputRate < 8000 || inputRate > 192000 ||
      !Number.isFinite(outputRate) || outputRate <= 0) throw new TypeError('지원하지 않는 녹음 표본 형식입니다.');
  const length = Math.min(outputRate * MAX_RECORDING_SECONDS, Math.floor(samples.length * outputRate / inputRate));
  const result = new Float32Array(length);
  const step = inputRate / outputRate;
  for (let index = 0; index < length; index += 1) {
    // Integrate each source interval when downsampling to reduce high-frequency aliasing.
    const start = index * step;
    const end = Math.min(samples.length, (index + 1) * step);
    if (step >= 1) {
      let sum = 0;
      for (let cursor = Math.floor(start); cursor < end; cursor += 1) {
        const weight = Math.min(end, cursor + 1) - Math.max(start, cursor);
        sum += (Number.isFinite(samples[cursor]) ? samples[cursor] : 0) * weight;
      }
      result[index] = sum / (end - start);
    } else {
      const left = Math.floor(start);
      const fraction = start - left;
      result[index] = (samples[left] ?? 0) * (1 - fraction) + (samples[Math.min(left + 1, samples.length - 1)] ?? 0) * fraction;
    }
  }
  return result;
}

export function encodeWavPCM16(samples) {
  if (!(samples instanceof Float32Array) || samples.length < TARGET_SAMPLE_RATE ||
      samples.length > TARGET_SAMPLE_RATE * MAX_RECORDING_SECONDS) throw new RangeError('원음은 1–15초여야 합니다.');
  const bytes = new Uint8Array(44 + samples.length * 2);
  const view = new DataView(bytes.buffer);
  const word = (offset, value) => { for (let index = 0; index < value.length; index += 1) bytes[offset + index] = value.charCodeAt(index); };
  word(0, 'RIFF'); view.setUint32(4, bytes.length - 8, true); word(8, 'WAVE');
  word(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, TARGET_SAMPLE_RATE, true); view.setUint32(28, TARGET_SAMPLE_RATE * 2, true);
  view.setUint16(32, 2, true); view.setUint16(34, 16, true); word(36, 'data'); view.setUint32(40, samples.length * 2, true);
  for (let index = 0; index < samples.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, Number.isFinite(samples[index]) ? samples[index] : 0));
    view.setInt16(44 + index * 2, sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767), true);
  }
  return bytes;
}

/** Incremental SSE framing, including CRLF split across network chunks and multiline data. */
export class SSEParser {
  constructor(onEvent, maxEventLength = 262144) {
    this.onEvent = onEvent;
    this.maxEventLength = maxEventLength;
    this.buffer = '';
    this.data = [];
    this.event = 'message';
    this.id = '';
    this.length = 0;
  }
  feed(chunk) {
    this.buffer += chunk;
    let match;
    while ((match = /\r\n|\r|\n/.exec(this.buffer))) {
      if (match[0] === '\r' && match.index === this.buffer.length - 1) break;
      const line = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      this.line(line);
    }
    if (this.buffer.length + this.length > this.maxEventLength) throw new Error('이벤트 크기가 상한을 넘었습니다.');
  }
  line(line) {
    if (line === '') {
      if (this.data.length) this.onEvent({ event: this.event, id: this.id, data: this.data.join('\n') });
      this.data = []; this.event = 'message'; this.length = 0;
      return;
    }
    this.length += line.length;
    if (this.length > this.maxEventLength) throw new Error('이벤트 크기가 상한을 넘었습니다.');
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'data') this.data.push(value);
    if (field === 'event') this.event = value || 'message';
    if (field === 'id' && !value.includes('\0')) this.id = value;
  }
}

/** A conservative server-time estimate: whole request RTT is added, so network delay expires early. */
export class ServerClock {
  constructor(monotonic = () => performance.now()) { this.monotonic = monotonic; this.anchor = null; this.tick = 0; }
  update(serverTime, roundTripMs = 0) {
    if (!Number.isFinite(serverTime)) return;
    const previous = this.anchor === null ? -Infinity : this.now();
    this.anchor = Math.max(previous, serverTime + Math.max(0, roundTripMs));
    this.tick = this.monotonic();
  }
  now() { return this.anchor === null ? Date.now() : this.anchor + Math.max(0, this.monotonic() - this.tick); }
}

/** Offer every observed message, even while disabled, so re-enabling never replays old history. */
export class AutoPlayQueue {
  constructor({ selfId, now = Date.now }) { this.selfId = selfId; this.now = now; this.enabled = false; this.seen = new Set(); this.pending = []; }
  isFresh(message) {
    const at = this.now();
    return Number.isFinite(message.acceptedAt) && Number.isFinite(message.autoPlayUntil) &&
      message.autoPlayUntil - message.acceptedAt === 30_000 && message.acceptedAt <= at && at < message.autoPlayUntil;
  }
  setEnabled(enabled) { this.enabled = Boolean(enabled); if (!this.enabled) this.pending = []; }
  offer(messages) {
    for (const message of messages) {
      if (!message || typeof message.id !== 'string' || this.seen.has(message.id)) continue;
      this.seen.add(message.id);
      if (this.enabled && message.sender?.id !== this.selfId && this.isFresh(message)) this.pending.push(message);
    }
    this.pending.sort((a, b) => a.sequence - b.sequence || a.acceptedAt - b.acceptedAt || a.id.localeCompare(b.id));
  }
  take() {
    if (!this.enabled) return null;
    while (this.pending.length) {
      const message = this.pending.shift();
      if (message.sender?.id !== this.selfId && this.isFresh(message)) return message;
    }
    return null;
  }
}

export function parseInvitation(value, origin) {
  try {
    const url = value.startsWith('#') ? new URL(value, origin) : new URL(value);
    if (url.origin !== origin || url.pathname !== '/' || url.search) return null;
    const match = /^#join=([a-f0-9]{24})\.([A-Za-z0-9_-]{43})$/.exec(url.hash);
    return match ? { roomId: match[1], inviteSecret: match[2] } : null;
  } catch { return null; }
}

export function naverNavigationUrl(destination, origin) {
  if (!Number.isFinite(destination.latitude) || destination.latitude < 31.43 || destination.latitude > 44.35 ||
      !Number.isFinite(destination.longitude) || destination.longitude < 122.37 || destination.longitude > 132) return null;
  const query = Object.entries({ dlat: String(destination.latitude), dlng: String(destination.longitude),
    dname: destination.name, appname: origin }).map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&');
  return `nmap://navigation?${query}`;
}
