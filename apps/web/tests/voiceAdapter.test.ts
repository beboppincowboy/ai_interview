import { afterEach, expect, it, vi } from "vitest";
import type { VoiceAnswer, VoiceToken } from "@/lib/api/types";
import { BrowserVoiceMedia, PcmResampler, encodePcm16 } from "@/lib/voice/audioCapture";
import { AudioPlayback } from "@/lib/voice/audioPlayback";
import { LiveVoiceAdapter, type VoiceCallbacks, type VoiceConnection, type VoiceMedia } from "@/lib/voice/liveClient";

afterEach(() => vi.useRealTimers());
const token: VoiceToken = { token: "test-ephemeral", model: "gemini-3.8-live", apiVersion: "v1beta", expiresAt: "2030-01-01", newSessionExpiresAt: "2030-01-01" };
const q = { id: "q1", text: "What did you build?", category: null, expectedSignals: [] };

function fixture({ earlySetup = true, failMint = false } = {}) {
  const callbacks: VoiceCallbacks[] = [];
  const connections: VoiceConnection[] = [];
  const answers: VoiceAnswer[] = [];
  let input: ((data: string) => void) | null = null;
  const media: VoiceMedia = { prepare: vi.fn().mockResolvedValue(undefined), setInput: vi.fn((callback) => { input = callback; }), play: vi.fn(), flush: vi.fn(), close: vi.fn() };
  const state = vi.fn();
  const adapter = new LiveVoiceAdapter({
    media, mint: failMint ? vi.fn().mockRejectedValue(new Error("mint rejected")) : vi.fn().mockResolvedValue(token),
    connect: vi.fn(async (_token, cb) => {
      callbacks.push(cb);
      const connection = { sendRealtimeInput: vi.fn(), sendClientContent: vi.fn(), close: vi.fn() };
      connections.push(connection);
      if (earlySetup) cb.onmessage({ setupComplete: {} });
      return connection;
    }),
    onAnswer: (answer) => answers.push(answer), onState: state,
  });
  return { adapter, media, callbacks, connections, answers, state, frame: () => input?.("pcm") };
}

it("does not mark an empty answer incomplete when token mint fails before capture", async () => {
  const f = fixture({ failMint: true }); await f.adapter.prepare();
  await expect(f.adapter.start("s1", q)).rejects.toThrow("Your transcript is still here");
  expect(f.answers).toEqual([]);
  expect(f.state).toHaveBeenLastCalledWith("recoverable", expect.any(String));
  f.adapter.close();
});

it("marks a pending captured tail incomplete when the connection fails", async () => {
  const f = fixture(); await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.frame(); f.callbacks[0].onerror();
  expect(f.answers.at(-1)).toMatchObject({ questionId: "q1", answerText: "", incomplete: true });
  f.adapter.close();
});

it("continuously downsamples split 48k frames into the same 16k PCM samples", () => {
  const samples = new Float32Array([0, .3, .6, -.9, -.6, -.3, 1, 1, 1, .4, .4, .4]);
  const whole = new PcmResampler(48_000).push(samples);
  const split = new PcmResampler(48_000);
  const result = [...split.push(samples.slice(0, 2)), ...split.push(samples.slice(2, 7)), ...split.push(samples.slice(7))];
  expect(result).toEqual([...whole]);
  expect(whole.length).toBe(4);
  const pcm = encodePcm16(new Float32Array([-1, 0, 1]));
  expect([...pcm]).toEqual([0, 128, 0, 0, 255, 127]);
});

it("plays output at 24k and cancels all queued sources on interruption", () => {
  const sources = [0, 1].map(() => ({ connect: vi.fn(), start: vi.fn(), stop: vi.fn(), disconnect: vi.fn(), buffer: null, onended: null }));
  const buffer = { copyToChannel: vi.fn(), duration: 1 / 24_000 };
  const context = { currentTime: 0, destination: {}, createBuffer: vi.fn(() => buffer), createBufferSource: vi.fn(() => sources.shift()) };
  const playback = new AudioPlayback(context as unknown as AudioContext);
  const first = sources[0], second = sources[1];
  playback.play("AAA="); playback.play("AAA=");
  expect(context.createBuffer).toHaveBeenCalledWith(1, 1, 24_000);
  playback.flush(); playback.flush();
  expect(first.stop).toHaveBeenCalledTimes(1);
  expect(second.stop).toHaveBeenCalledTimes(1);
});

it("waits for setup, including setup before connect resolves, and emits canonical opening once", async () => {
  const f = fixture();
  await f.adapter.prepare(); await f.adapter.start("s1", q);
  expect(f.connections[0].sendClientContent).toHaveBeenCalledTimes(1);
  f.frame();
  expect(f.connections[0].sendRealtimeInput).toHaveBeenCalledWith({ audio: { data: "pcm", mimeType: "audio/pcm;rate=16000" } });
  f.adapter.close();
});

it("records the conversation as alternating interviewer and candidate turns", async () => {
  const f = fixture();
  await f.adapter.prepare(); await f.adapter.start("s1", q);
  const say = (content: object) => f.callbacks[0].onmessage({ serverContent: content });
  say({ outputTranscription: { text: "Why Kafka" } }); say({ outputTranscription: { text: "?" } });
  say({ inputTranscription: { text: "For ordering." } });
  say({ outputTranscription: { text: "How did you handle retries?" } });
  say({ inputTranscription: { text: "With backoff.", finished: true } });
  expect(f.answers.at(-1)).toMatchObject({
    interviewerText: "Why Kafka?\nHow did you handle retries?",
    answerText: "For ordering.\nWith backoff.",
    turns: [
      { speaker: "interviewer", text: "Why Kafka?" },
      { speaker: "candidate", text: "For ordering." },
      { speaker: "interviewer", text: "How did you handle retries?" },
      { speaker: "candidate", text: "With backoff." }
    ]
  });
  f.adapter.close();
});

it("attributes independent transcripts to their question and ignores obsolete epochs", async () => {
  vi.useFakeTimers();
  const f = fixture();
  await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.callbacks[0].onmessage({ serverContent: { inputTranscription: { text: "First " }, turnComplete: true } });
  f.callbacks[0].onmessage({ serverContent: { inputTranscription: { text: "answer", finished: true }, outputTranscription: { text: "Question one" } } });
  const drain = f.adapter.drain(); await vi.advanceTimersByTimeAsync(2_000); await drain;
  await f.adapter.start("s1", { ...q, id: "q2" });
  const count = f.answers.length;
  f.callbacks[0].onmessage({ serverContent: { inputTranscription: { text: "late" } } });
  expect(f.answers).toHaveLength(count);
  f.callbacks[1].onmessage({ serverContent: { inputTranscription: { text: "Second answer" } } });
  expect(f.answers.at(-1)).toMatchObject({ questionId: "q2", answerText: "Second answer", incomplete: true });
  expect(f.answers.find((a) => a.answerText === "First answer")).toMatchObject({ questionId: "q1", incomplete: false });
  f.adapter.close();
});

it("preserves mute across a new question and flushes interrupted playback without completing an answer", async () => {
  vi.useFakeTimers();
  const f = fixture();
  await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.adapter.setMuted(true); f.frame();
  expect(f.connections[0].sendRealtimeInput).not.toHaveBeenCalledWith({ audio: { data: "pcm", mimeType: "audio/pcm;rate=16000" } });
  f.callbacks[0].onmessage({ serverContent: { inputTranscription: { text: "Partial" }, interrupted: true, turnComplete: true } });
  expect(f.media.flush).toHaveBeenCalled();
  expect(f.answers.at(-1)?.incomplete).toBe(true);
  const drain = f.adapter.drain(); await vi.advanceTimersByTimeAsync(2_000); await drain;
  await f.adapter.start("s1", { ...q, id: "q2" });
  f.frame(); expect(f.connections[1].sendRealtimeInput).not.toHaveBeenCalled();
  f.adapter.setMuted(false); f.frame(); expect(f.connections[1].sendRealtimeInput).toHaveBeenCalledTimes(1);
  f.adapter.close();
});

it("treats GoAway and socket errors as recoverable and disposes the microphone", async () => {
  const f = fixture(); await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.callbacks[0].onmessage({ goAway: { timeLeft: "10s" } });
  expect(f.state).toHaveBeenLastCalledWith("recoverable", expect.any(String));
  expect(f.media.close).toHaveBeenCalled();
  expect(f.connections[0].close).toHaveBeenCalled();
  f.adapter.close();
});

it("times out unacknowledged setup and closes a late connection", async () => {
  vi.useFakeTimers(); const f = fixture({ earlySetup: false }); await f.adapter.prepare();
  const start = f.adapter.start("s1", q).catch(() => undefined);
  await vi.advanceTimersByTimeAsync(10_000); await start;
  expect(f.state).toHaveBeenLastCalledWith("recoverable", expect.any(String));
  expect(f.connections[0].close).toHaveBeenCalled();
  expect(f.connections[0].sendClientContent).not.toHaveBeenCalled();
  f.adapter.close();
});

it("releases media when preparation resolves after cancellation", async () => {
  const f = fixture(); let resolve!: () => void;
  vi.mocked(f.media.prepare).mockImplementation(() => new Promise<void>((done) => { resolve = done; }));
  const prepare = f.adapter.prepare().catch(() => undefined);
  f.adapter.close(); resolve(); await prepare;
  expect(f.media.close).toHaveBeenCalled();
  expect(f.connections).toHaveLength(0);
});

it("preserves uncertainty from a recovered partial answer after later finalized input", async () => {
  const f = fixture(); await f.adapter.prepare();
  await f.adapter.start("s1", q, { questionId: q.id, interviewerText: "Question", answerText: "Partial", incomplete: true });
  f.callbacks[0].onmessage({ serverContent: { inputTranscription: { text: "Continuation", finished: true } } });
  expect(f.answers.at(-1)).toMatchObject({ answerText: "Partial\nContinuation", incomplete: true });
  f.adapter.close();
});

function browserMediaFixture() {
  const stop = vi.fn();
  const stream = { getTracks: () => [{ stop }] } as unknown as MediaStream;
  const source = { connect: vi.fn(), disconnect: vi.fn() };
  const node = { port: { onmessage: null as ((event: MessageEvent<Float32Array>) => void) | null, close: vi.fn() }, connect: vi.fn(), disconnect: vi.fn() };
  const context = { sampleRate: 48_000, state: "running", resume: vi.fn().mockResolvedValue(undefined), close: vi.fn().mockResolvedValue(undefined), audioWorklet: { addModule: vi.fn().mockResolvedValue(undefined) }, destination: {}, currentTime: 0, createMediaStreamSource: vi.fn(() => source) };
  const microphone = vi.fn().mockResolvedValue(stream);
  const media = new BrowserVoiceMedia({ context: () => context as unknown as AudioContext, microphone, worklet: () => node as unknown as AudioWorkletNode });
  return { media, microphone, context, node, stop, stream };
}

it("batches worklet frames into 20ms input packets and stops real media tracks on close", async () => {
  const f = browserMediaFixture(); const send = vi.fn();
  const prepare = f.media.prepare();
  expect(f.context.resume).toHaveBeenCalled();
  await prepare; f.media.setInput(send);
  for (let i = 0; i < 10; i++) f.node.port.onmessage!({ data: new Float32Array(128) } as MessageEvent<Float32Array>);
  expect(send).toHaveBeenCalledTimes(1);
  expect(atob(send.mock.calls[0][0]).length).toBe(640);
  f.media.close(); f.media.close();
  expect(f.stop).toHaveBeenCalledTimes(1);
});

it("stops a browser microphone stream that resolves after close", async () => {
  const f = browserMediaFixture(); let resolve!: (stream: MediaStream) => void;
  f.microphone.mockImplementation(() => new Promise<MediaStream>((done) => { resolve = done; }));
  const prepare = f.media.prepare().catch(() => undefined);
  f.media.close(); resolve(f.stream); await prepare;
  expect(f.stop).toHaveBeenCalledTimes(1);
  expect(f.context.createMediaStreamSource).not.toHaveBeenCalled();
});

it("plays every PCM part and rotates a connection into explicit recovery", async () => {
  vi.useFakeTimers(); const f = fixture(); await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.callbacks[0].onmessage({ serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: "audio/pcm;rate=24000", data: "AAA=" } }, { inlineData: { mimeType: "audio/pcm;rate=24000", data: "AQE=" } }] } } });
  expect(f.media.play).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(8 * 60_000);
  expect(f.state).toHaveBeenLastCalledWith("recoverable", expect.any(String));
  expect(f.media.close).toHaveBeenCalled(); f.adapter.close();
});


it("announces interviewer audio and the return to listening", async () => {
  const f = fixture();
  await f.adapter.prepare(); await f.adapter.start("s1", q);
  f.callbacks[0].onmessage({ serverContent: { modelTurn: { parts: [{ inlineData: { data: "AAA=", mimeType: "audio/pcm;rate=24000" } }] } } });
  expect(f.state).toHaveBeenLastCalledWith("speaking");
  f.callbacks[0].onmessage({ serverContent: { turnComplete: true } });
  expect(f.state).toHaveBeenLastCalledWith("listening");
  f.adapter.close();
});
