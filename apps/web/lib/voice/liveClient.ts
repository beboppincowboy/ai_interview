import { GoogleGenAI, type LiveCallbacks, type LiveServerMessage, type Session } from "@google/genai";
import type { VoiceAnswer, VoiceQuestion, VoiceToken } from "@/lib/api/types";
import { ApiError } from "@/lib/api/client";
import { friendlyError } from "@/lib/errorMessages";
import { mintVoiceToken } from "@/lib/query/voice";
import { BrowserVoiceMedia } from "./audioCapture";
import { VoiceTranscriptBuffer } from "./transcript";

export type VoiceConnection = Pick<Session, "sendRealtimeInput" | "sendClientContent" | "close">;
export type VoiceEvent = Pick<LiveServerMessage, "setupComplete" | "serverContent" | "goAway">;
export type VoiceCallbacks = { onmessage: (event: VoiceEvent) => void; onerror: () => void; onclose: () => void };
export type VoiceState = "preparing" | "connecting" | "listening" | "ending" | "recoverable" | "closed";
export interface VoiceMedia {
  prepare(): Promise<void>;
  setInput(callback: ((data: string) => void) | null): void;
  play(data: string): void;
  flush(): void;
  close(): void;
}
type Options = {
  media?: VoiceMedia;
  mint?: typeof mintVoiceToken;
  connect?: (token: VoiceToken, callbacks: VoiceCallbacks) => Promise<VoiceConnection>;
  onAnswer: (answer: VoiceAnswer) => void;
  onState: (state: VoiceState, message?: string) => void;
};

const connectLive = (token: VoiceToken, callbacks: VoiceCallbacks): Promise<VoiceConnection> =>
  new GoogleGenAI({ apiKey: token.token, httpOptions: { apiVersion: token.apiVersion } }).live.connect({
    model: token.model, callbacks: callbacks as LiveCallbacks,
    // The constrained credential supplies the complete server-controlled setup. No tools or resumption config.
  });

/** One question/epoch at a time. The app, never provider events, owns Next, End and Save. */
export class LiveVoiceAdapter {
  private readonly media: VoiceMedia;
  private epoch = 0;
  private closed = false;
  private prepared = false;
  private preparation = 0;
  private muted = false;
  private draining = false;
  private connection: VoiceConnection | null = null;
  private abort: AbortController | null = null;
  private buffer: VoiceTranscriptBuffer | null = null;
  private setupTimer: ReturnType<typeof setTimeout> | null = null;
  private rotationTimer: ReturnType<typeof setTimeout> | null = null;
  private finishDrain: (() => void) | null = null;
  private drainPromise: Promise<void> | null = null;
  private rejectSetup: ((error: Error) => void) | null = null;
  private inputPending = false;

  constructor(private readonly options: Options) { this.media = options.media ?? new BrowserVoiceMedia(); }

  async prepare() {
    if (this.closed) throw new DOMException("Canceled", "AbortError");
    const preparation = ++this.preparation;
    this.options.onState("preparing");
    try {
      await this.media.prepare();
      if (this.closed || preparation !== this.preparation) throw new DOMException("Canceled", "AbortError");
      this.prepared = true;
    } catch (error) {
      if (!this.closed && preparation === this.preparation) { this.media.close(); this.options.onState("recoverable", "Microphone unavailable. Continue by typing, or try voice again."); }
      throw error;
    }
  }

  setMuted(muted: boolean) {
    this.muted = muted;
    if (muted && this.connection && !this.draining) {
      try { this.connection.sendRealtimeInput({ audioStreamEnd: true }); }
      catch { this.fail(this.epoch, "Voice disconnected. Your transcript is still here."); }
    }
  }

  async start(sessionId: string, question: VoiceQuestion, seed?: VoiceAnswer) {
    if (this.closed || !this.prepared) throw new DOMException("Microphone is not ready", "AbortError");
    this.finishDrain?.();
    this.disconnect();
    const epoch = this.epoch;
    this.draining = false; this.inputPending = false;
    this.buffer = new VoiceTranscriptBuffer(question.id, seed);
    this.abort = new AbortController();
    this.options.onState("connecting");
    try {
      const token = await (this.options.mint ?? mintVoiceToken)(sessionId, question.id, this.abort.signal);
      if (!this.current(epoch)) throw new DOMException("Canceled", "AbortError");
      let resolveSetup!: () => void;
      const setup = new Promise<void>((resolve, reject) => { resolveSetup = resolve; this.rejectSetup = reject; });
      let setupReceived = false;
      const callbacks: VoiceCallbacks = {
        onmessage: (event) => {
          if (!this.current(epoch)) return;
          if (event.setupComplete) { setupReceived = true; resolveSetup(); }
          if (event.goAway && !this.draining) { this.fail(epoch, "Voice needs a fresh connection. Continue voice to reconnect to this question."); return; }
          const content = event.serverContent;
          if (!content) return;
          if (content.interrupted) this.media.flush();
          if (content.inputTranscription?.finished) this.inputPending = false;
          if (content.inputTranscription || content.outputTranscription) this.options.onAnswer(this.buffer!.update(content));
          try {
            for (const part of content.modelTurn?.parts ?? []) {
              if (part.inlineData?.data && part.inlineData.mimeType?.startsWith("audio/pcm") && !this.draining) this.media.play(part.inlineData.data);
            }
          } catch { this.fail(epoch, "Voice playback stopped. Your transcript is still here."); }
        },
        onerror: () => this.fail(epoch, "Voice disconnected. Your transcript is still here."),
        onclose: () => { if (!this.draining) this.fail(epoch, "Voice disconnected. Your transcript is still here."); }
      };
      this.setupTimer = setTimeout(() => this.fail(epoch, "Voice did not become ready. Try again or continue by typing."), 10_000);
      const connecting = (this.options.connect ?? connectLive)(token, callbacks).then((connection) => {
        if (!this.current(epoch)) { connection.close(); throw new DOMException("Canceled", "AbortError"); }
        this.connection = connection;
        return connection;
      });
      const [connection] = await Promise.all([connecting, setup]);
      if (!this.current(epoch) || !setupReceived) throw new DOMException("Canceled", "AbortError");
      clearTimeout(this.setupTimer!); this.setupTimer = null; this.rejectSetup = null;
      connection.sendClientContent({ turns: [{ role: "user", parts: [{ text: seed?.answerText
        ? `Continue the same question: ${question.text}\nVisible partial answer: ${seed.answerText}\nAsk me to continue this answer. Do not move to another question.`
        : `Read this question aloud, then wait for my answer: ${question.text}` }] }], turnComplete: true });
      this.media.setInput((data) => {
        if (!this.current(epoch) || this.muted || this.draining) return;
        try { this.inputPending = true; connection.sendRealtimeInput({ audio: { data, mimeType: "audio/pcm;rate=16000" } }); }
        catch { this.fail(epoch, "Voice disconnected. Your transcript is still here."); }
      });
      this.rotationTimer = setTimeout(() => this.fail(epoch, "Voice needs a fresh connection. Continue voice to reconnect to this question."), 8 * 60_000);
      this.options.onState("listening");
    } catch (error) {
      if (this.current(epoch)) this.fail(epoch, error instanceof ApiError ? friendlyError(error) : "Voice could not connect. Try again or continue by typing.");
      // Never expose SDK errors, which can contain the credential-bearing connection URL.
      // eslint-disable-next-line preserve-caught-error -- retaining the SDK cause would retain the ephemeral token URL
      throw new Error("Voice could not connect. Your transcript is still here.");
    }
  }

  drain(): Promise<void> {
    if (this.drainPromise) return this.drainPromise;
    this.draining = true;
    this.media.setInput(null); this.media.flush();
    if (this.rotationTimer) clearTimeout(this.rotationTimer);
    this.rotationTimer = null;
    this.options.onState("ending");
    if (!this.connection) return Promise.resolve();
    try { this.connection.sendRealtimeInput({ audioStreamEnd: true }); }
    catch { this.fail(this.epoch, "Voice disconnected while ending. Review the answer tail."); return Promise.resolve(); }
    this.drainPromise = new Promise((resolve) => {
      const timer = setTimeout(() => finish(), 2_000);
      const finish = () => {
        clearTimeout(timer); this.finishDrain = null; this.drainPromise = null;
        if (this.buffer) this.options.onAnswer(this.inputPending ? this.buffer.uncertain() : this.buffer.snapshot());
        this.disconnect(); resolve();
      };
      this.finishDrain = finish;
    });
    return this.drainPromise;
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.preparation++;
    this.finishDrain?.(); this.disconnect(); this.media.close(); this.prepared = false;
    this.options.onState("closed");
  }

  private current(epoch: number) { return !this.closed && epoch === this.epoch; }
  private fail(epoch: number, message: string) {
    if (!this.current(epoch)) return;
    if (this.buffer) this.options.onAnswer(this.buffer.uncertain());
    this.finishDrain?.(); this.disconnect(); this.media.close(); this.prepared = false;
    this.options.onState("recoverable", message);
  }
  private disconnect() {
    this.epoch++;
    this.abort?.abort(); this.abort = null;
    this.rejectSetup?.(new DOMException("Canceled", "AbortError")); this.rejectSetup = null;
    if (this.setupTimer) clearTimeout(this.setupTimer);
    if (this.rotationTimer) clearTimeout(this.rotationTimer);
    this.setupTimer = null; this.rotationTimer = null;
    this.media.setInput(null); this.media.flush();
    const connection = this.connection; this.connection = null; connection?.close();
  }
}
