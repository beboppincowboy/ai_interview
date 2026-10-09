import { act, configure, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, it, vi } from "vitest";
import { JOB_TEXT, RESUME_TEXT, renderRoute, setupMockBackend } from "./render";

type Callbacks = { onAnswer: (answer: { questionId: string; interviewerText: string; answerText: string; incomplete: boolean }) => void; onState: (state: string, message?: string) => void };
const audio = vi.hoisted(() => {
  // setup.ts eagerly imports pages; clear that cache before installing this file's audio/config mocks.
  vi.resetModules();
  return { prepare: vi.fn(), start: vi.fn(), close: vi.fn(), drain: vi.fn(), mute: vi.fn(), callbacks: [] as Callbacks[] };
});
vi.mock("@/lib/api/config", async (original) => ({ ...await original<object>(), VOICE_ENABLED: true }));
vi.mock("@/lib/voice/liveClient", () => ({ LiveVoiceAdapter: class {
  constructor(options: Callbacks) { audio.callbacks.push(options); }
  prepare = audio.prepare; start = audio.start; close = audio.close; drain = audio.drain; setMuted = audio.mute;
} }));
const { store, server } = setupMockBackend();
// The voice page is lazy-loaded; under a parallel full run its first render can take longer than the 1s default.
configure({ asyncUtilTimeout: 5_000 });
beforeEach(() => {
  audio.callbacks.length = 0;
  audio.prepare.mockRejectedValue(new DOMException("Permission denied", "NotAllowedError"));
  audio.start.mockResolvedValue(undefined); audio.drain.mockReset().mockResolvedValue(undefined);
});

async function readySet() {
  const resume = store.pasteResume({ name: "Backend", text: RESUME_TEXT }).body.resume;
  const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
  const set = store.createPracticeSet({ resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" }).body;
  await new Promise((resolve) => setTimeout(resolve, 40));
  return store.getPracticeSet(set.id);
}

it("requests a microphone only after Start and supports two typed answers after denial", async () => {
  const set = await readySet(); const user = userEvent.setup();
  renderRoute(`/voice/${set.id}`);
  const start = await screen.findByRole("button", { name: "Start interview" });
  expect(audio.prepare).not.toHaveBeenCalled();
  await user.click(start);
  expect(await screen.findByText(/microphone unavailable/i)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Type instead" }));
  await user.type(screen.getByLabelText("Your answer"), "First answer");
  await user.click(screen.getByRole("button", { name: "Next question" }));
  await user.type(screen.getByLabelText("Your answer"), "Second answer");
  await user.click(screen.getByRole("button", { name: "End interview" }));
  expect(await screen.findByRole("heading", { name: "Review transcript" })).toBeInTheDocument();
  expect(screen.getByText(`2 of ${Math.min(6, set.questions.length)} answered`)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Save interview" })).toBeEnabled();
  expect(audio.prepare).toHaveBeenCalledTimes(1);
  expect(audio.close).toHaveBeenCalled();
});

it("allows early End, review correction and Save with a single answered question", async () => {
  const set = await readySet(); const user = userEvent.setup();
  const { router } = renderRoute(`/voice/${set.id}`);
  await user.click(await screen.findByRole("button", { name: "Type instead" }));
  await user.type(screen.getByLabelText("Your answer"), "Partial answer");
  await user.click(screen.getByRole("button", { name: "End interview" }));
  const answer = screen.getByLabelText("Answer 1");
  await user.clear(answer); await user.type(answer, "Corrected saved answer");
  await user.click(screen.getByRole("button", { name: "Save interview" }));
  await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/voice\/sessions\//));
  expect(store.history().voiceSessions[0].answeredCount).toBe(1);
  expect(audio.prepare).not.toHaveBeenCalled();
});

it("saves answers typed in review after the microphone was denied and no draft existed yet", async () => {
  const set = await readySet(); const user = userEvent.setup();
  const { router } = renderRoute(`/voice/${set.id}`);
  await user.click(await screen.findByRole("button", { name: "Start interview" }));
  expect(await screen.findByText(/microphone unavailable/i)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "End interview" }));
  await user.type(screen.getByLabelText("Answer 1"), "Typed in review");
  await user.click(screen.getByRole("button", { name: "Save interview" }));
  await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/voice\/sessions\//));
  expect(store.history().voiceSessions[0].answeredCount).toBe(1);
});

it("freezes review and Discard after a lost Save reply until GET confirms its outcome", async () => {
  const set = await readySet(); const user = userEvent.setup(); let savedId = ""; let posts = 0; let unavailable = true;
  server.use(
    http.post("*/api/voice-sessions/:id/save", async ({ params, request }) => {
      posts++; savedId = String(params.id); store.saveVoiceSession(savedId, await request.json());
      return HttpResponse.error();
    }),
    http.get("*/api/voice-sessions/:id", ({ params }) => unavailable ? HttpResponse.json({ code: "SERVICE_UNAVAILABLE", message: "Unavailable" }, { status: 503 }) : HttpResponse.json(store.getVoiceSession(String(params.id))))
  );
  const { router } = renderRoute(`/voice/${set.id}`);
  await user.click(await screen.findByRole("button", { name: "Type instead" }));
  await user.type(screen.getByLabelText("Your answer"), "Keep this answer");
  await user.click(screen.getByRole("button", { name: "End interview" }));
  await user.dblClick(screen.getByRole("button", { name: "Save interview" }));
  await screen.findByRole("button", { name: "Check save outcome" }, { timeout: 4_000 });
  expect(posts).toBe(1);
  expect(screen.getByLabelText("Answer 1")).toBeDisabled();
  expect(screen.getByRole("button", { name: "Discard interview" })).toBeDisabled();
  unavailable = false;
  await user.click(screen.getByRole("button", { name: "Check save outcome" }));
  await waitFor(() => expect(router.state.location.pathname).toBe(`/voice/sessions/${savedId}`));
  expect(posts).toBe(1);
});

it("blocks an empty Save and warns before leaving unsaved text", async () => {
  const set = await readySet(); const user = userEvent.setup(); const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const { router } = renderRoute(`/voice/${set.id}`);
  await user.click(await screen.findByRole("button", { name: "Type instead" }));
  await user.type(screen.getByLabelText("Your answer"), "Unsaved answer");
  await user.click(screen.getByRole("link", { name: "History" }));
  expect(confirm).toHaveBeenCalled();
  expect(router.state.location.pathname).toBe(`/voice/${set.id}`);
  await user.click(screen.getByRole("button", { name: "End interview" }));
  await user.clear(screen.getByLabelText("Answer 1"));
  expect(screen.getByRole("button", { name: "Save interview" })).toBeDisabled();
});


it("accepts exactly 4,000 answer characters and keeps oversized text editable without truncation", async () => {
  const set = await readySet();
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Type instead" }));
  fireEvent.change(await screen.findByLabelText("Your answer"), { target: { value: "a".repeat(4000) } });
  await userEvent.click(screen.getByRole("button", { name: "End interview" }));
  expect(screen.getByRole("button", { name: "Save interview" })).toBeEnabled();
  fireEvent.change(screen.getByLabelText("Answer 1"), { target: { value: "a".repeat(4001) } });
  expect(screen.getByLabelText("Answer 1")).toHaveValue("a".repeat(4001));
  expect(screen.getByRole("button", { name: "Save interview" })).toBeDisabled();
  expect(screen.getByText(/Each answer must be 4,000/)).toBeInTheDocument();
});

it("keeps mute across Next and voice recovery, attributes late events, and focuses transitions", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  await userEvent.click(await screen.findByRole("button", { name: "Mute microphone" }));
  const first = audio.callbacks[0];
  act(() => first.onAnswer({ questionId: set.questions[0].id, interviewerText: "Question one", answerText: "Spoken first answer", incomplete: false }));
  await userEvent.click(screen.getByRole("button", { name: "Next question" }));
  await waitFor(() => expect(audio.start).toHaveBeenCalledTimes(2));
  expect(screen.getByRole("heading", { name: set.questions[1].text })).toHaveFocus();
  expect(screen.getByRole("button", { name: "Mute microphone" })).toHaveAttribute("aria-pressed", "true");
  act(() => first.onState("recoverable", "Connection lost. Your transcript is still here."));
  await userEvent.click(screen.getByRole("button", { name: "Continue voice" }));
  await waitFor(() => expect(audio.start).toHaveBeenCalledTimes(3));
  expect(audio.mute).toHaveBeenLastCalledWith(true);
  act(() => first.onAnswer({ questionId: set.questions[0].id, interviewerText: "", answerText: "obsolete", incomplete: false }));
  await userEvent.click(screen.getByRole("button", { name: "End interview" }));
  expect(screen.getByRole("heading", { name: "Review transcript" })).toHaveFocus();
  expect(screen.getByLabelText("Answer 1")).toHaveValue("Spoken first answer");
});

it("unmount during a pending microphone request disposes it without creating a session or starting audio", async () => {
  const set = await readySet(); let resolve!: () => void;
  audio.prepare.mockImplementationOnce(() => new Promise<void>((done) => { resolve = done; }));
  let creates = 0;
  server.use(http.post("*/api/voice-sessions", () => { creates++; return HttpResponse.json({}); }));
  const view = renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  view.unmount();
  await act(async () => resolve());
  expect(audio.close).toHaveBeenCalled();
  expect(audio.start).not.toHaveBeenCalled();
  expect(creates).toBe(0);
});

it("keeps token-failure guidance and typed text, then clears stale guidance on a new attempt", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  const detail = "This interview has reached its voice connection limit. Continue by typing.";
  audio.start.mockImplementationOnce(async () => {
    audio.callbacks.at(-1)!.onState("recoverable", detail);
    throw new Error("sanitized connection failure");
  });
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Type instead" }));
  fireEvent.change(await screen.findByLabelText("Your answer"), { target: { value: "Keep my typed answer" } });
  await userEvent.click(screen.getByRole("button", { name: "Continue voice" }));
  await screen.findByText(detail, {}, { timeout: 1_000 });
  await userEvent.click(screen.getByRole("button", { name: "Type instead" }));
  expect(await screen.findByLabelText("Your answer")).toHaveValue("Keep my typed answer");
  expect(audio.prepare).toHaveBeenCalledTimes(1);
  audio.start.mockRejectedValueOnce(new Error("another connection failure"));
  await userEvent.click(screen.getByRole("button", { name: "Continue voice" }));
  await screen.findByText(/Microphone unavailable or voice could not connect/);
  expect(screen.queryByText(detail)).not.toBeInTheDocument();
});

it("keeps the adapter's rate-limit guidance when connecting the next question fails", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  await screen.findByRole("button", { name: "Mute microphone" });
  const detail = "Too many requests. Wait a moment, then try again.";
  audio.start.mockImplementationOnce(async () => {
    audio.callbacks.at(-1)!.onState("recoverable", detail);
    throw new Error("sanitized connection failure");
  });
  await userEvent.click(screen.getByRole("button", { name: "Next question" }));
  expect(await screen.findByText(detail, {}, { timeout: 1_000 })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Type instead" })).toBeEnabled();
});

it("unlocks review only after a failed Save is confirmed DRAFT, then allows a successful retry", async () => {
  const set = await readySet(); let posts = 0;
  server.use(http.post("*/api/voice-sessions/:id/save", async ({ params, request }) => {
    posts++;
    if (posts === 1) return HttpResponse.error();
    return HttpResponse.json(store.saveVoiceSession(String(params.id), await request.json()));
  }));
  const { router } = renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Type instead" }));
  fireEvent.change(await screen.findByLabelText("Your answer"), { target: { value: "Draft answer" } });
  await userEvent.click(screen.getByRole("button", { name: "End interview" }));
  await userEvent.click(screen.getByRole("button", { name: "Save interview" }));
  await screen.findByText(/still a draft/);
  expect(screen.getByLabelText("Answer 1")).toBeEnabled();
  await userEvent.click(screen.getByRole("button", { name: "Save interview" }));
  await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/voice\/sessions\//));
  expect(posts).toBe(2);
});

it("preserves a removed-source transcript read-only and offers Discard without another write", async () => {
  const set = await readySet(); let deletes = 0;
  server.use(
    http.post("*/api/voice-sessions/:id/save", () => HttpResponse.error()),
    http.get("*/api/voice-sessions/:id", () => HttpResponse.json({ code: "NOT_FOUND", message: "Removed" }, { status: 404 })),
    http.delete("*/api/voice-sessions/:id/draft", () => { deletes++; return new HttpResponse(null, { status: 204 }); })
  );
  const { router } = renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Type instead" }));
  fireEvent.change(await screen.findByLabelText("Your answer"), { target: { value: "Readable after deletion" } });
  await userEvent.click(screen.getByRole("button", { name: "End interview" }));
  await userEvent.click(screen.getByRole("button", { name: "Save interview" }));
  await screen.findByText(/interview or its source was removed/);
  expect(screen.getByLabelText("Answer 1")).toHaveValue("Readable after deletion");
  expect(screen.getByLabelText("Answer 1")).toBeDisabled();
  expect(screen.getByRole("button", { name: "Save interview" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Discard interview" }));
  await waitFor(() => expect(router.state.location.pathname).toBe("/history"));
  expect(deletes).toBe(0);
});


it("enforces the 64 KiB UTF-8 boundary including transcript metadata", async () => {
  const { transcriptBytes, transcriptProblem } = await import("@/lib/voice/useVoiceInterview");
  const answer = { questionId: "q1", interviewerText: "", answerText: "Valid answer", incomplete: false };
  const overhead = transcriptBytes({ answers: [answer] });
  const remaining = 65_536 - overhead;
  answer.interviewerText = "界".repeat(Math.floor(remaining / 3)) + "a".repeat(remaining % 3);
  const transcript = { answers: [answer] };
  expect(transcriptBytes(transcript)).toBe(65_536);
  expect(transcriptProblem(transcript)).toBeNull();
  answer.interviewerText += "a";
  expect(transcriptProblem(transcript)).toMatch(/64 KiB/);
});

it("blocks Start while generation is pending or failed and provides generation retry", async () => {
  const set = await readySet();
  server.use(http.get("*/api/practice-sets/:id", () => HttpResponse.json({ ...set, status: "FAILED", questions: [], latestJob: { id: "generation", status: "FAILED", stage: "GENERATE", attempts: 1, maxAttempts: 3, error: { code: "AI_UNAVAILABLE", message: "Generation failed", retryable: true } } })));
  renderRoute(`/voice/${set.id}`);
  expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Start interview" })).not.toBeInTheDocument();
  expect(audio.prepare).not.toHaveBeenCalled();
});

it("closes media at the run deadline and keeps reviewed Save available", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  server.use(http.post("*/api/voice-sessions", () => {
    const draft = store.createVoiceSession(set.id);
    return HttpResponse.json({ ...draft, runDeadline: new Date(Date.now() + 250).toISOString() });
  }));
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  await waitFor(() => expect(audio.start).toHaveBeenCalled());
  act(() => audio.callbacks[0].onAnswer({ questionId: set.questions[0].id, interviewerText: "Question", answerText: "Answer before time limit", incomplete: true }));
  expect(await screen.findByRole("heading", { name: "Review transcript" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Save interview" })).toBeEnabled();
  expect(audio.close).toHaveBeenCalled();
  expect(audio.start).toHaveBeenCalledTimes(1);
});

it("drains active voice at the run deadline before review and keeps the final callback", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  let finishDrain!: () => void;
  audio.drain.mockImplementationOnce(() => new Promise<void>((resolve) => { finishDrain = resolve; }));
  server.use(http.post("*/api/voice-sessions", () => {
    const draft = store.createVoiceSession(set.id);
    return HttpResponse.json({ ...draft, runDeadline: new Date(Date.now() + 1_000).toISOString() });
  }));
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  await waitFor(() => expect(audio.start).toHaveBeenCalled());
  await waitFor(() => expect(audio.drain).toHaveBeenCalledTimes(1), { timeout: 2_000 });
  expect(screen.queryByRole("heading", { name: "Review transcript" })).not.toBeInTheDocument();
  act(() => audio.callbacks[0].onAnswer({ questionId: set.questions[0].id, interviewerText: "Question", answerText: "Final drain transcript", incomplete: true }));
  await act(async () => finishDrain());
  expect(await screen.findByRole("heading", { name: "Review transcript" })).toBeInTheDocument();
  expect(screen.getByLabelText("Answer 1")).toHaveValue("Final drain transcript");
  expect(audio.close).toHaveBeenCalled();
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(audio.drain).toHaveBeenCalledTimes(1);
});


it("drains pending voice transcription before switching to typing", async () => {
  const set = await readySet(); audio.prepare.mockResolvedValue(undefined);
  renderRoute(`/voice/${set.id}`);
  await userEvent.click(await screen.findByRole("button", { name: "Start interview" }));
  await screen.findByRole("button", { name: "Mute microphone" });
  audio.drain.mockImplementationOnce(async () => {
    audio.callbacks[0].onAnswer({ questionId: set.questions[0].id, interviewerText: "Question", answerText: "Last captured words", incomplete: true });
  });
  await userEvent.click(screen.getByRole("button", { name: "Type instead" }));
  expect(await screen.findByLabelText("Your answer")).toHaveValue("Last captured words");
  expect(audio.close).toHaveBeenCalled();
});
