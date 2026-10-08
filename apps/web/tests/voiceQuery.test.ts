import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createVoiceSession, deleteVoiceSession, discardVoiceDraft, getVoiceSession, mintVoiceToken,
  retryVoiceReport, saveVoiceSession
} from "@/lib/query/voice";

const response = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("voice requests", () => {
  it("mints once on an upstream failure, with only the canonical question reference", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(503, { code: "VOICE_TOKEN_UNAVAILABLE", message: "Unavailable" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(mintVoiceToken("session", "question")).rejects.toMatchObject({ code: "VOICE_TOKEN_UNAVAILABLE" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/voice-sessions/session/tokens");
    expect(JSON.parse(options.body)).toEqual({ questionId: "question" });
    expect(options.cache).toBe("no-store");
  });

  it("aborts token provisioning at five seconds without a retry", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
    }));
    vi.stubGlobal("fetch", fetchMock);
    const result = mintVoiceToken("session", "question").catch((error: unknown) => error);

    await vi.advanceTimersByTimeAsync(5_000);

    expect(await result).toMatchObject({ kind: "TIMEOUT" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("leaves an ambiguous Save to session reconciliation instead of automatically resubmitting", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError("Connection lost"));
    vi.stubGlobal("fetch", fetchMock);

    await expect(saveVoiceSession("session", { answers: [] })).rejects.toMatchObject({ kind: "NETWORK" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("uses the typed create, read, Save, report retry and delete routes", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response(200, { id: "session" })));
    vi.stubGlobal("fetch", fetchMock);
    const transcript = { answers: [{ questionId: "question", interviewerText: "Asked", answerText: "Reviewed", incomplete: true }] };

    await createVoiceSession("set");
    await getVoiceSession("session");
    await saveVoiceSession("session", transcript);
    await retryVoiceReport("session");
    await discardVoiceDraft("session");
    await deleteVoiceSession("session");

    expect(fetchMock.mock.calls.map(([path, options]) => [path, options.method])).toEqual([
      ["/api/voice-sessions", "POST"],
      ["/api/voice-sessions/session", "GET"],
      ["/api/voice-sessions/session/save", "POST"],
      ["/api/voice-sessions/session/report/retry", "POST"],
      ["/api/voice-sessions/session/draft", "DELETE"],
      ["/api/voice-sessions/session", "DELETE"]
    ]);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ practiceSetId: "set" });
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual(transcript);
  });
});
