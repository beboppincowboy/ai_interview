/** Streams provider PCM16 at its declared 24 kHz, independent of the device's output rate. */
export class AudioPlayback {
  private readonly sources = new Set<AudioBufferSourceNode>();
  private nextTime = 0;

  constructor(private readonly context: AudioContext) {}

  play(data: string) {
    const bytes = Uint8Array.from(atob(data), (c) => c.charCodeAt(0));
    if (bytes.length % 2) throw new Error("Invalid PCM audio");
    if (!bytes.length) return;
    const view = new DataView(bytes.buffer);
    const samples = new Float32Array(bytes.length / 2);
    for (let i = 0; i < samples.length; i++) samples[i] = view.getInt16(i * 2, true) / 32768;
    const buffer = this.context.createBuffer(1, samples.length, 24_000);
    buffer.copyToChannel(samples, 0);
    const source = this.context.createBufferSource();
    source.buffer = buffer;
    source.connect(this.context.destination);
    source.onended = () => { this.sources.delete(source); source.disconnect(); };
    this.sources.add(source);
    const start = Math.max(this.context.currentTime, this.nextTime);
    source.start(start);
    this.nextTime = start + buffer.duration;
  }

  flush() {
    for (const source of this.sources) {
      source.onended = null;
      source.stop(); source.disconnect();
    }
    this.sources.clear();
    this.nextTime = this.context.currentTime;
  }
}
