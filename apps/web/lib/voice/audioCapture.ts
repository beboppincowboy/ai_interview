import workletUrl from "./capture.worklet.ts?worker&url";
import { AudioPlayback } from "./audioPlayback";
import type { VoiceMedia } from "./liveClient";

/** Area averaging retains the fractional sample boundary between AudioWorklet frames. */
export class PcmResampler {
  private filled = 0;
  private sum = 0;
  private readonly ratio: number;
  constructor(sampleRate: number) {
    if (!Number.isFinite(sampleRate) || sampleRate <= 0) throw new Error("Invalid microphone sample rate");
    this.ratio = sampleRate / 16_000;
  }
  push(frame: Float32Array): Float32Array {
    const output: number[] = [];
    for (const sample of frame) {
      let remaining = 1;
      while (remaining > 1e-9) {
        const used = Math.min(remaining, this.ratio - this.filled);
        this.sum += sample * used; this.filled += used; remaining -= used;
        if (this.filled >= this.ratio - 1e-9) {
          output.push(this.sum / this.ratio); this.filled = 0; this.sum = 0;
        }
      }
    }
    return new Float32Array(output);
  }
}

export function encodePcm16(samples: Float32Array): Uint8Array {
  const bytes = new Uint8Array(samples.length * 2);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < samples.length; i++) {
    const sample = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(i * 2, Math.round(sample * (sample < 0 ? 32768 : 32767)), true);
  }
  return bytes;
}

type MediaDependencies = {
  context: () => AudioContext;
  microphone: () => Promise<MediaStream>;
  worklet: (context: AudioContext) => AudioWorkletNode;
};
const browserMedia: MediaDependencies = {
  context: () => new AudioContext(),
  microphone: () => navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }),
  worklet: (context) => new AudioWorkletNode(context, "voice-capture")
};

export class BrowserVoiceMedia implements VoiceMedia {
  private epoch = 0;
  private context: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private node: AudioWorkletNode | null = null;
  private playback: AudioPlayback | null = null;
  private input: ((data: string) => void) | null = null;
  private resampler: PcmResampler | null = null;
  private pendingSamples: number[] = [];

  constructor(private readonly dependencies = browserMedia) {}

  // Called directly from Start/Continue voice. Context creation and resume happen before any await.
  async prepare() {
    this.close();
    const epoch = this.epoch;
    const context = this.dependencies.context();
    this.context = context;
    const resumed = context.resume();
    void resumed.catch(() => undefined);
    try {
      const stream = await this.dependencies.microphone();
      if (this.epoch !== epoch) { stream.getTracks().forEach((track) => track.stop()); throw new DOMException("Canceled", "AbortError"); }
      this.stream = stream;
      await resumed;
      await context.audioWorklet.addModule(workletUrl);
      if (this.epoch !== epoch) throw new DOMException("Canceled", "AbortError");
      this.playback = new AudioPlayback(context);
      this.resampler = new PcmResampler(context.sampleRate);
      this.source = context.createMediaStreamSource(stream);
      this.node = this.dependencies.worklet(context);
      this.node.port.onmessage = (event: MessageEvent<Float32Array>) => {
        if (epoch !== this.epoch || !this.input) return;
        this.pendingSamples.push(...this.resampler!.push(event.data));
        while (this.pendingSamples.length >= 320 && this.input) {
          const pcm = encodePcm16(new Float32Array(this.pendingSamples.splice(0, 320)));
          this.input(btoa(Array.from(pcm, (byte) => String.fromCharCode(byte)).join("")));
        }
      };
      this.source.connect(this.node);
      // The worklet's output is silence. Connecting it keeps processing scheduled without mic feedback.
      this.node.connect(context.destination);
    } catch (error) {
      if (this.epoch === epoch) this.close();
      throw error;
    }
  }

  setInput(callback: ((data: string) => void) | null) {
    this.input = callback;
    this.pendingSamples = [];
    if (this.context) this.resampler = new PcmResampler(this.context.sampleRate);
  }
  play(data: string) { this.playback?.play(data); }
  flush() { this.playback?.flush(); }
  close() {
    this.epoch++; this.input = null;
    this.pendingSamples = [];
    this.flush(); this.playback = null;
    if (this.node) { this.node.port.onmessage = null; this.node.port.close(); this.node.disconnect(); }
    this.source?.disconnect();
    this.stream?.getTracks().forEach((track) => track.stop());
    if (this.context && this.context.state !== "closed") void this.context.close().catch(() => undefined);
    this.node = null; this.source = null; this.stream = null; this.context = null; this.resampler = null;
  }
}
