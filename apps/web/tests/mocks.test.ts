import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { JobAccepted, JobStatusResponse, ResumeCreated, ResumeDetail, ResumeExtractionResult, TargetJobCreated } from "@/lib/api/types";
import { createMockServer } from "@/mocks/server";
import { createMockStore, type MockStoreOptions } from "@/mocks/store";
import { errorOf, get, pollJobs, post, registerApiScenarios } from "./apiScenarios";

let clock = Date.parse("2026-09-28T12:00:00Z");
const timing: MockStoreOptions = { now: () => clock, queuedMs: 100, stageMs: 100 };
const { store, server } = createMockServer(timing);
const finishJobs = () => { clock += 10_000; };

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
afterEach(() => store.reset());
afterAll(() => server.close());

const RESUME_TEXT = "Sample resume text. ".repeat(10);
const JOB_TEXT = "Sample job description for a backend engineer. ".repeat(5);

async function pasteResume(name = "Backend", text = RESUME_TEXT) {
  return (await post<ResumeCreated>("/api/resumes/paste", { name, jobTitle: null, text })).resume;
}

async function targetJob() {
  return (await post<TargetJobCreated>("/api/target-jobs", { name: "Acme", text: JOB_TEXT })).targetJob;
}

describe("mock API", () => {
  // The scenarios liveApi.test.ts also runs against a real API. Here every job finishes when the clock jumps.
  registerApiScenarios({ base: "", settle: async () => finishJobs() });

  // The rest need mock-only controls (store uploads, forced failures, the clock, storage) or an empty database.

  // Upload rules are tested on the store: jsdom's File loses its name inside the fetch FormData, so multipart
  // parsing in the handler is covered by the browser smoke run instead.
  it("resolves an uploaded file whose text matches a saved resume to that resume", async () => {
    const saved = await pasteResume("Pasted");
    const upload = store.uploadResume({ fileName: "resume.txt", size: RESUME_TEXT.length, content: RESUME_TEXT, name: "Uploaded" });
    expect(upload).toMatchObject({ status: 202, body: { duplicate: false, resume: { status: "PROCESSING" } } });

    finishJobs();
    const job = await get<JobStatusResponse<ResumeExtractionResult>>(`/api/jobs/${upload.body.resume.latestJob!.jobId}`);
    expect(job.status).toBe("SUCCEEDED");
    expect(job.result?.duplicateOf).toEqual({ id: saved.id, name: "Pasted" });
    expect(await errorOf(get(`/api/resumes/${upload.body.resume.id}`))).toMatchObject({ status: 404, code: "RESUME_NOT_FOUND" });
  });

  it("returns the same resume at once when the same file is uploaded twice, and rejects other file types", () => {
    const file = { fileName: "cv.pdf", size: 11, content: "%PDF sample" };
    const first = store.uploadResume(file);
    expect(store.uploadResume(file)).toMatchObject({ status: 200, body: { duplicate: true, resume: { id: first.body.resume.id } } });
    expect(() => store.uploadResume({ ...file, fileName: "cv.png" })).toThrow(expect.objectContaining({ code: "UNSUPPORTED_FILE_TYPE" }));
  });

  it("fails a forced job with the documented error shape and keeps the attempt for retry", async () => {
    const resume = await pasteResume();
    store.failNext("RESUME_SCORE", { retryable: false });
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    finishJobs();
    const job = await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`);
    expect(job).toMatchObject({ status: "FAILED", attempts: 1, maxAttempts: 3, result: null });
    expect(job.error).toEqual({ code: "GEMINI_SAFETY", message: "Simulated failure", retryable: false });
  });

  it("reports a failed job as a runtime failure when settling against a real API", async () => {
    const resume = await pasteResume();
    store.failNext("RESUME_SCORE", { retryable: false });
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    finishJobs();
    await expect(pollJobs("")([accepted.jobId])).rejects
      .toThrow(`Runtime failure, not a contract disagreement: RESUME_SCORE job ${accepted.jobId} failed with GEMINI_SAFETY`);
  });

  it("fails a job that never ends at its polling deadline", async () => {
    const resume = await pasteResume();
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    // The clock does not move, so the job stays queued.
    await expect(pollJobs("", { timeoutMs: 50, intervalMs: 10 })([accepted.jobId])).rejects
      .toThrow(`RESUME_SCORE job ${accepted.jobId} was still QUEUED after 0.05s`);
  });

  it("shows a retrying job before a retryable failure", async () => {
    const resume = await pasteResume();
    store.failNext("RESUME_SCORE");
    const accepted = await post<JobAccepted>(`/api/resumes/${resume.id}/score`);
    clock += 250;
    expect(await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`)).toMatchObject({ status: "RETRYING", attempts: 2 });
    finishJobs();
    expect(await get<JobStatusResponse>(`/api/jobs/${accepted.jobId}`)).toMatchObject({ status: "FAILED", attempts: 3 });
  });

  it("keeps a resume, its score and a pending job across a reload from storage", async () => {
    const storage = new Map<string, string>();
    const local = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
      removeItem: (key: string) => void storage.delete(key)
    };
    const first = createMockStore({ ...timing, storage: local });
    const { body } = first.pasteResume({ name: "Kept", jobTitle: null, text: RESUME_TEXT });
    const scored = first.scoreResume(body.resume.id);
    finishJobs();
    first.getResume(body.resume.id);
    const pending = first.runFit(body.resume.id, first.createTargetJob({ name: "Acme", text: JOB_TEXT }).body.targetJob.id);

    const reloaded = createMockStore({ ...timing, storage: local });
    const detail: ResumeDetail = reloaded.getResume(body.resume.id);
    expect(detail.score).not.toBeNull();
    expect(detail.latestJob?.jobId).toBe(scored.jobId);
    expect(reloaded.getJob(pending.jobId).status).toBe("QUEUED");
    finishJobs();
    expect(reloaded.getJob(pending.jobId).status).toBe("SUCCEEDED");
  });

  it("refuses suggestions without other sources", async () => {
    const resume = await pasteResume();
    const job = await targetJob();
    const view = await get<{ sourcesAvailable: boolean }>(`/api/resumes/${resume.id}/target-jobs/${job.id}/suggestions`);
    expect(view.sourcesAvailable).toBe(false);
    expect(await errorOf(post(`/api/resumes/${resume.id}/target-jobs/${job.id}/suggestions`)))
      .toMatchObject({ status: 409, code: "NO_EXPERIENCE_SOURCES" });
  });
});
