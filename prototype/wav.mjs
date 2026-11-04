export const SAMPLE_RATE = 16_000;
export const MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 15;
export const MAX_WAV_HEADER_BYTES = 4096;
export const MAX_WAV_BYTES = MAX_AUDIO_BYTES + MAX_WAV_HEADER_BYTES;

/** Validate the bytes, including chunk boundaries; MIME and client duration are not evidence. */
export function inspectWav(bytes) {
  const fail = () => { throw new Error('1–15초 PCM16 mono 16kHz WAV 원음이 필요합니다.'); };
  if (!Buffer.isBuffer(bytes) || bytes.length < 44 || bytes.length > MAX_WAV_BYTES) fail();
  if (bytes.toString('latin1', 0, 4) !== 'RIFF' || bytes.toString('latin1', 8, 12) !== 'WAVE' ||
      bytes.readUInt32LE(4) !== bytes.length - 8) fail();
  let format = false;
  let data;
  let offset = 12;
  while (offset < bytes.length) {
    if (offset + 8 > bytes.length) fail();
    const kind = bytes.toString('latin1', offset, offset + 4);
    const length = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    const end = start + length;
    const paddedEnd = end + (length % 2);
    if (end > bytes.length || paddedEnd > bytes.length) fail();
    if (kind === 'fmt ') {
      if (format || data || ![16, 18].includes(length)) fail();
      if (length === 18 && bytes.readUInt16LE(start + 16) !== 0) fail();
      if (bytes.readUInt16LE(start) !== 1 || bytes.readUInt16LE(start + 2) !== 1 ||
          bytes.readUInt32LE(start + 4) !== SAMPLE_RATE || bytes.readUInt32LE(start + 8) !== SAMPLE_RATE * 2 ||
          bytes.readUInt16LE(start + 12) !== 2 || bytes.readUInt16LE(start + 14) !== 16) fail();
      format = true;
    } else if (kind === 'data') {
      if (!format || data || length % 2 !== 0) fail();
      data = { byteLength: length, sampleCount: length / 2 };
    }
    offset = paddedEnd;
  }
  if (!format || !data || bytes.length - data.byteLength > MAX_WAV_HEADER_BYTES ||
      data.sampleCount < SAMPLE_RATE || data.sampleCount > SAMPLE_RATE * 15) fail();
  return { ...data, durationMs: data.sampleCount / SAMPLE_RATE * 1000,
    format: 'pcm_s16le', channels: 1, sampleRate: SAMPLE_RATE };
}
