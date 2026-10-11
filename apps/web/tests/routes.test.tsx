import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { JOB_TEXT, RESUME_TEXT, renderRoute, setupMockBackend } from "./render";

const { store, server } = setupMockBackend();

describe("routes", () => {
  it("shows the deleted state for a deleted practice set", async () => {
    const resume = store.pasteResume({ name: "Backend", jobTitle: null, text: RESUME_TEXT }).body.resume;
    const job = store.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob;
    const set = store.createPracticeSet({ resumeId: resume.id, targetJobId: job.id, mode: "PRACTICE" }).body.practiceSet;
    store.deleteResume(resume.id);
    renderRoute(`/practice/${set.id}`);
    expect(await screen.findByRole("heading", { name: "This practice set was deleted" })).toBeInTheDocument();
  });

  it("renders the deleted state for an unknown path without calling the API", async () => {
    const requests: string[] = [];
    server.events.on("request:start", ({ request }) => requests.push(request.url));
    renderRoute("/no-such-page");
    expect(await screen.findByRole("heading", { name: "This item was deleted" })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(requests).toEqual([]);
  });

  it("goes from the resume picker to the score step and back", async () => {
    const resume = store.pasteResume({ name: "Backend", jobTitle: null, text: RESUME_TEXT }).body.resume;
    const user = userEvent.setup();
    const { router } = renderRoute("/flow");
    await user.click(await screen.findByRole("button", { name: /backend/i }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/flow/${resume.id}`));

    router.history.back();
    await waitFor(() => expect(router.state.location.pathname).toBe("/flow"));
    expect(await screen.findByRole("button", { name: /backend/i })).toBeInTheDocument();
  });

  it("links stepper steps once their IDs exist and disables the rest", async () => {
    const resume = store.pasteResume({ name: "Backend", jobTitle: null, text: RESUME_TEXT }).body.resume;
    renderRoute(`/flow/${resume.id}`);
    const stepper = await screen.findByRole("navigation", { name: "Progress" });
    expect(stepper.querySelector(`a[href="/flow/${resume.id}/jobs"]`)).not.toBeNull();
    expect(stepper.querySelector('a[href="/flow"]')).not.toBeNull();
    expect(stepper.querySelectorAll('[aria-disabled="true"]')).toHaveLength(3);
  });
});
