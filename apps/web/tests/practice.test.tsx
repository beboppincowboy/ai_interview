import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { JOB_TEXT, RESUME_TEXT, renderRoute, setupMockBackend } from "./render";

// Stages long enough to observe pending states, short enough to keep the suite fast.
const { store, server } = setupMockBackend({ stageMs: 150 });
const LONG_ANSWER = "At Sample Co I owned the checkout latency work: I measured p99, moved two calls off the hot path and cut it by 40%.";

function newSet() {
  const resume = store.pasteResume({ name: "Backend", jobTitle: null, text: RESUME_TEXT }).body.resume;
  const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
  const set = store.createPracticeSet({ resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" }).body.practiceSet;
  return set;
}

async function answer(user: ReturnType<typeof userEvent.setup>, text: string) {
  const box = screen.getByLabelText("Your answer");
  await user.clear(box);
  await user.type(box, text);
  await user.click(screen.getByRole("button", { name: /submit/i }));
}

describe("practice", () => {
  it("shows generation progress, then questions with why tags", async () => {
    const set = newSet();
    // Test jobs finish in milliseconds, so serve the first read as it looked while generating.
    server.use(http.get("*/api/practice-sets/:id", () => HttpResponse.json(set), { once: true }));
    renderRoute(`/practice/${set.id}`);
    expect(await screen.findByRole("status")).toHaveTextContent(/choosing questions|waiting/i);
    expect(await screen.findByText(/why this question/i, {}, { timeout: 4_000 })).toBeInTheDocument();
    expect(screen.getAllByRole("tab").length).toBeGreaterThanOrEqual(3);
  });

  it("scores two attempts and shows the change, and blocks an unchanged answer", async () => {
    const set = newSet();
    const user = userEvent.setup();
    renderRoute(`/practice/${set.id}`);
    await screen.findByLabelText("Your answer", {}, { timeout: 4_000 });

    await answer(user, "Short answer.");
    expect(await screen.findByText("Scoring in progress")).toBeInTheDocument();
    expect(await screen.findByText("Attempt 1", {}, { timeout: 4_000 })).toBeInTheDocument();

    await user.type(screen.getByLabelText("Your answer"), "Short answer.");
    expect(screen.getByRole("button", { name: /submit new attempt/i })).toBeDisabled();
    expect(screen.getByText("This is the same as your last attempt.")).toBeInTheDocument();

    await answer(user, LONG_ANSWER);
    expect(await screen.findByText("Attempt 2", {}, { timeout: 4_000 })).toBeInTheDocument();
    expect(screen.getByLabelText(/change from previous: \+\d+/i)).toBeInTheDocument();
    expect(screen.getByText("Earlier attempts")).toBeInTheDocument();
  }, 15_000);

  it("keeps a draft when switching questions and back", async () => {
    const set = newSet();
    const user = userEvent.setup();
    renderRoute(`/practice/${set.id}`);
    await user.type(await screen.findByLabelText("Your answer", {}, { timeout: 4_000 }), "Half-written draft");
    const tabs = screen.getAllByRole("tab");
    await user.click(tabs[1]);
    expect(screen.getByLabelText("Your answer")).toHaveValue("");
    await user.click(tabs[0]);
    expect(screen.getByLabelText("Your answer")).toHaveValue("Half-written draft");
  });

  it("adds your own question and blocks the eleventh", async () => {
    const set = newSet();
    await new Promise((resolve) => setTimeout(resolve, 300));
    for (let i = 1; i <= 9; i++) store.addQuestion(set.id, `My own question number ${i}?`);
    const user = userEvent.setup();
    renderRoute(`/practice/${set.id}`);
    await user.click(await screen.findByRole("button", { name: /add your own question/i }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByLabelText("Question"), "Why do you want to work here?");
    await user.click(within(dialog).getByRole("button", { name: "Add question" }));

    expect(await screen.findByText("Added by you")).toBeInTheDocument();
    expect(screen.getByText("Why do you want to work here?", { selector: "p" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add your own question/i })).toBeDisabled();
    expect(screen.getByText(/maximum of 10/)).toBeInTheDocument();
  });

  it("keeps a failed attempt's text and scores it on retry", async () => {
    const set = newSet();
    store.failNext("ANSWER_FEEDBACK", { retryable: false });
    const user = userEvent.setup();
    renderRoute(`/practice/${set.id}`);
    await screen.findByLabelText("Your answer", {}, { timeout: 4_000 });
    await answer(user, LONG_ANSWER);

    expect(await screen.findByRole("alert", {}, { timeout: 4_000 })).toBeInTheDocument();
    expect(screen.getByText(LONG_ANSWER)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(screen.getByText("Attempt 1")).toBeInTheDocument(), { timeout: 4_000 });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  }, 15_000);
});
