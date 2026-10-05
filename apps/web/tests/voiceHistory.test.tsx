import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import type { VoiceSession } from "@/lib/api/types";
import { renderRoute, setupMockBackend } from "./render";

const { server } = setupMockBackend();
const session: VoiceSession = {
  id: "v1", practiceSetId: "p1", resumeId: "r1", targetJobId: "j1", status: "SAVED",
  questions: [{ id: "q1", text: "What did you build?", category: null, expectedSignals: [] }, { id: "q2", text: "Why?", category: null, expectedSignals: [] }],
  transcript: { answers: [{ questionId: "q1", interviewerText: "What did you build?", answerText: "A corrected answer", incomplete: true }, { questionId: "q2", interviewerText: "", answerText: "", incomplete: false }] },
  submissionJobId: "job1", reportJobId: "job1", createdAt: "2026-10-04T10:00:00Z", runDeadline: "2026-10-04T10:20:00Z", draftExpiresAt: "2026-10-05T10:00:00Z", savedAt: "2026-10-04T10:01:00Z",
  report: { selectedCount: 2, answeredCount: 1, overallScore: 0, weakestQuestionIds: ["q1"], unansweredQuestionIds: ["q2"], answers: [{ questionId: "q1", score: 0, incomplete: true, summary: "Needs more evidence", strengths: [], gaps: ["Missing result"], betterAnswerOutline: ["Explain impact"], nextStep: null, followUpQuestion: null }] }
};

it("reopens saved transcripts and reports without requesting a microphone or token", async () => {
  let tokens = 0;
  server.use(http.get("*/api/voice-sessions/v1", () => HttpResponse.json(session)), http.post("*/api/voice-sessions/v1/tokens", () => { tokens++; return new HttpResponse(null, { status: 500 }); }));
  renderRoute("/voice/sessions/v1");
  expect(await screen.findByText("A corrected answer")).toBeInTheDocument();
  expect(screen.getByText("Score 0/100")).toBeInTheDocument();
  expect(screen.getByText("Capture may be incomplete")).toBeInTheDocument();
  expect(screen.getByText("Unanswered")).toBeInTheDocument();
  expect(tokens).toBe(0);
});

it("retries a failed report using its replacement job while retaining the saved transcript", async () => {
  const failed = { ...session, report: null };
  let retried = false;
  server.use(
    http.get("*/api/voice-sessions/v1", () => HttpResponse.json(retried ? { ...session, reportJobId: "job2" } : failed)),
    http.get("*/api/jobs/job1", () => HttpResponse.json({ status: "FAILED", error: { code: "AI_UNAVAILABLE", message: "Failed", retryable: false } })),
    http.post("*/api/voice-sessions/v1/report/retry", () => { retried = true; return HttpResponse.json({ ...failed, reportJobId: "job2" }); }),
    http.get("*/api/jobs/job2", () => HttpResponse.json({ status: "SUCCEEDED" }))
  );
  renderRoute("/voice/sessions/v1");
  await userEvent.click(await screen.findByRole("button", { name: "Retry report" }));
  expect(await screen.findByText("Score 0/100")).toBeInTheDocument();
  expect(screen.getByText("A corrected answer")).toBeInTheDocument();
});

it("requires confirmation before deleting a saved interview and never refetches it afterwards", async () => {
  let deleted = false;
  let readsAfterDelete = 0;
  server.use(
    http.get("*/api/voice-sessions/v1", () => {
      if (!deleted) return HttpResponse.json(session);
      readsAfterDelete++;
      return HttpResponse.json({ code: "VOICE_SESSION_NOT_FOUND", message: "Not found" }, { status: 404 });
    }),
    http.delete("*/api/voice-sessions/v1", () => { deleted = true; return new HttpResponse(null, { status: 204 }); })
  );
  const { router } = renderRoute("/voice/sessions/v1");
  await userEvent.click(await screen.findByRole("button", { name: "Delete interview" }));
  expect(deleted).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "Delete permanently" }));
  await waitFor(() => expect(deleted).toBe(true));
  await waitFor(() => expect(router.state.location.pathname).toBe("/history"));
  expect(readsAfterDelete).toBe(0);
});

it("keeps the saved transcript visible while polling a temporarily unavailable report", async () => {
  let calls = 0;
  server.use(
    http.get("*/api/voice-sessions/v1", () => HttpResponse.json({ ...session, report: null })),
    http.get("*/api/jobs/job1", () => { calls++; return HttpResponse.json({ code: "SERVICE_UNAVAILABLE", message: "Temporarily unavailable" }, { status: 503 }); })
  );
  const { router } = renderRoute("/voice/sessions/v1");
  expect(await screen.findByText("A corrected answer")).toBeInTheDocument();
  await waitFor(() => expect(calls).toBeGreaterThan(0));
  expect(router.state.location.pathname).toBe("/voice/sessions/v1");
});
