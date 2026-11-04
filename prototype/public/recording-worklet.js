/* AudioWorklet runs on the audio thread. Both transfer chunks and total captured frames are bounded. */
class VoiceCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = true;
    this.frames = 0;
    this.limit = Math.floor(sampleRate * 15);
    this.chunk = new Float32Array(2048);
    this.used = 0;
    this.port.onmessage = ({ data }) => {
      if (data?.type === 'stop') { this.active = false; this.flush(); this.port.postMessage({ type: 'stopped' }); }
    };
  }
  flush() {
    if (!this.used) return;
    const samples = this.chunk.slice(0, this.used);
    this.port.postMessage({ type: 'samples', samples }, [samples.buffer]);
    this.used = 0;
  }
  process(inputs, outputs) {
    for (const output of outputs) for (const channel of output) channel.fill(0);
    if (!this.active) return false;
    const channels = inputs[0];
    if (!channels?.length) return true;
    for (let index = 0; index < channels[0].length && this.frames < this.limit; index += 1) {
      let sample = 0;
      for (const channel of channels) sample += channel[index] ?? 0;
      this.chunk[this.used++] = sample / channels.length;
      this.frames += 1;
      if (this.used === this.chunk.length) this.flush();
    }
    if (this.frames >= this.limit) {
      this.active = false;
      this.flush();
      this.port.postMessage({ type: 'limit' });
      return false;
    }
    return true;
  }
}
registerProcessor('voice-capture', VoiceCapture);
