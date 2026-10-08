import { expect, it } from "vitest";
import { createMockStore } from "@/mocks/store";

function fixture() {
  let clock = 1_000_000;
  const store = createMockStore({ now: () => clock, queuedMs: 100, stageMs: 100 });
  const resume = store.pasteResume({ name: "Backend", text: "Resume evidence ".repeat(10) }).body.resume;
  const job = store.createTargetJob({ name: "Acme", text: "Backend job ".repeat(10) }).body.targetJob;
  const set = store.createPracticeSet({ resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" }).body;
  clock += 10_000;
  store.getPracticeSet(set.id);
  const draft = store.createVoiceSession(set.id);
  const transcript = { answers: draft.questions.map((q, i) => ({ questionId: q.id, interviewerText: q.text, answerText: i === 0 ? " Reviewed answer " : "", incomplete: i === 0 })) };
  return { store, resume, job, draft, transcript, finish: () => { clock += 10_000; } };
}

it("keeps Save identity and immutable text across failed-report Retry", () => {
  const { store, draft, transcript, finish } = fixture();
  expect(store.history().voiceSessions).toEqual([]);
  store.failNext("VOICE_REPORT", { retryable: false });
  const saved = store.saveVoiceSession(draft.id, transcript);
  const submission = saved.session.submissionJobId;
  finish();
  expect(store.history().voiceSessions[0].reportStatus).toBe("FAILED");
  const retry = store.retryVoiceReport(draft.id);
  expect(retry.submissionJobId).toBe(submission);
  expect(retry.reportJobId).not.toBe(submission);
  expect(store.saveVoiceSession(draft.id, transcript)).toMatchObject({ replayed: true, session: { submissionJobId: submission, reportJobId: retry.reportJobId } });
  expect(() => store.saveVoiceSession(draft.id, { answers: [{ ...transcript.answers[0], answerText: "Changed" }] })).toThrow(expect.objectContaining({ code: "VOICE_SESSION_ALREADY_SAVED" }));
  expect(() => store.deleteVoiceSession(draft.id, true)).toThrow(expect.objectContaining({ code: "VOICE_SESSION_ALREADY_SAVED" }));
  finish();
  expect(store.getVoiceSession(draft.id).report?.answeredCount).toBe(1);
});

it("rejects empty, duplicate, foreign and oversized answers without submitting jobs", () => {
  const { store, draft, transcript } = fixture();
  for (const answers of [[], [transcript.answers[0], transcript.answers[0]], [{ ...transcript.answers[0], questionId: "foreign" }], [{ ...transcript.answers[0], answerText: "x".repeat(4_001) }]]) {
    expect(() => store.saveVoiceSession(draft.id, { answers })).toThrow();
  }
  expect(store.getVoiceSession(draft.id).submissionJobId).toBeNull();
});
