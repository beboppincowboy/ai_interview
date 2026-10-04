// API scenarios shared by the MSW fake (mocks.test.ts) and a real API (liveApi.test.ts, opt-in).
// They hold on a persistent, shared database: every pasted text is unique, and lists are checked for what they contain
// or omit rather than compared whole. Every response they read is recorded as a shape and compared with one committed
// file, so a field that one side renames or drops fails the run against that side.
import { expect, it } from "vitest";
import { apiRequest } from "@/lib/api/client";
import type {
  Attempt, DeleteImpact, History, JobAccepted, JobStatusResponse, PracticeSet, Resume, ResumeCreated, SuggestionsView,
  TargetJobCreated
} from "@/lib/api/types";

export type ApiTarget = {
  /** Prefix for every request path: empty for the fake, an origin such as http://127.0.0.1:3000 for a real API. */
  base: string;
  /** Resolves once every listed job has finished. */
  settle: (jobIds: string[]) => Promise<void>;
};

export const post = <T,>(path: string, body: unknown = {}) => apiRequest<T>(path, { method: "POST", body, retries: 0 });
export const get = <T,>(path: string) => apiRequest<T>(path, { retries: 0 });
export const errorOf = (promise: Promise<unknown>) => promise.then(() => null, (error: unknown) => error);

/** A real API's `settle`: polls each job until it ends. A failed job means the API broke at runtime, not contract drift. */
export const pollJobs = (base: string) => async (jobIds: string[]) => {
  for (const jobId of jobIds) {
    for (;;) {
      const job = await get<JobStatusResponse>(`${base}/api/jobs/${jobId}`);
      if (job.status === "SUCCEEDED") break;
      if (job.status === "FAILED") {
        throw new Error(`Runtime failure, not a contract disagreement: ${job.jobType} job ${jobId} failed with ${job.error?.code ?? "no error code"}`);
      }
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
  }
};

// Close enough to a real resume and job ad that a real model finds matched and missing requirements.
// The reference line makes each text unique, so a rerun never hits the duplicate check.
const resumeText = () => [
  "Backend Engineer",
  "Software Engineer, Example Payments, 2021 to 2026",
  "Built Kotlin and Spring Boot services that process card payments.",
  "Worked on backend services.",
  "Helped with database work in PostgreSQL.",
  "Skills: Kotlin, Java, Spring Boot, PostgreSQL, Redis, Docker",
  `Reference ${crypto.randomUUID()}`
].join("\n");

const jobText = () => [
  "Acme is hiring a backend engineer to build Kotlin and Spring Boot services on PostgreSQL.",
  "Requirements: 3 or more years of backend work, Kotlin or Java, PostgreSQL, Kafka event streaming and Kubernetes.",
  `Reference ${crypto.randomUUID()}`
].join("\n");

// These result arrays can be empty, but their stable item fields remain part of the API contract.
const OPTIONAL_ARRAY_FIELDS: Record<string, string[]> = {
  "result.items": [
    "guidance",
    "match",
    "requirement",
    "source",
    "source.id",
    "source.name",
    "source.type",
    "whyItFits"
  ],
  "result.rewrites": ["original", "placeholders", "rewritten", "section"]
};

/** Key paths of a JSON value. A list contributes its first item, as `path[]`. */
export function shapeOf(value: unknown, path = ""): string[] {
  if (Array.isArray(value)) {
    const declaredFields = OPTIONAL_ARRAY_FIELDS[path];
    if (declaredFields) {
      const expected = declaredFields.map((field) => `${path}[].${field}`).sort();
      for (const item of value) {
        const observed = shapeOf(item, `${path}[]`).sort();
        if (observed.length !== expected.length || observed.some((field, index) => field !== expected[index])) {
          throw new Error(`Unexpected item shape at ${path}: expected ${expected.join(", ")}; got ${observed.join(", ")}`);
        }
      }
      return expected;
    }
    return value.length ? shapeOf(value[0], `${path}[]`) : [];
  }
  if (value === null || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, child]) => {
    const childPath = path ? `${path}.${key}` : key;
    return [childPath, ...shapeOf(child, childPath)];
  });
}

/** Records a response shape under its scenario label. */
export function recordShape<T>(shapes: Record<string, string[]>, name: string, body: T): T {
  const shape = shapeOf(body).sort();
  const previous = shapes[name];
  if (previous) {
    if (previous.length !== shape.length || previous.some((field, index) => field !== shape[index])) {
      throw new Error(`Response shape changed between occurrences of ${name}`);
    }
  } else {
    shapes[name] = shape;
  }
  return body;
}

/** History cut to the items with these IDs, so its shape describes what the scenario made, not whatever is listed first. */
const ownHistory = (history: History, keep: string[]) => ({
  ...history,
  resumes: history.resumes.filter((item) => keep.includes(item.id)),
  targetJobs: history.targetJobs.filter((item) => keep.includes(item.id)),
  practiceSets: history.practiceSets.filter((item) => keep.includes(item.id))
});

const ids = (items: { id: string }[]) => items.map((item) => item.id);

/** Registers the shared scenarios, then one test comparing every recorded shape with tests/apiShapes.json. */
export function registerApiScenarios({ base, settle }: ApiTarget) {
  const shapes: Record<string, string[]> = {};
  const record = <T,>(name: string, body: T) => recordShape(shapes, name, body);

  const paste = async (name: string, text = resumeText()) => record("POST /api/resumes/paste (new)",
    await post<ResumeCreated>(`${base}/api/resumes/paste`, { name, jobTitle: null, text })).resume;
  const targetJob = async () => record("POST /api/target-jobs (new)",
    await post<TargetJobCreated>(`${base}/api/target-jobs`, { name: "Acme", text: jobText() })).targetJob;
  const createSet = async (resumeId: string, targetJobId: string, label = "POST /api/practice-sets (new)") => record(label,
    await post<PracticeSet>(`${base}/api/practice-sets`, { resumeId, targetJobId, mode: "PRACTICE" }));
  const readSet = (id: string) => get<PracticeSet>(`${base}/api/practice-sets/${id}`);
  const readJob = async (jobId: string) => {
    const job = await get<JobStatusResponse>(`${base}/api/jobs/${jobId}`);
    return record(`GET /api/jobs/{id} (${job.jobType})`, job);
  };

  it("resolves a pasted duplicate to the saved resume", async () => {
    const text = resumeText();
    const saved = await paste("First", text);
    const again = record("POST /api/resumes/paste (duplicate)",
      await post<ResumeCreated>(`${base}/api/resumes/paste`, { name: "Second", jobTitle: null, text: `  ${text}  ` }));
    expect(again).toMatchObject({ duplicate: true, resume: { id: saved.id, name: "First" } });
  });

  it("previews and cascades a resume delete", async () => {
    const resume = await paste("Backend");
    const other = await paste("Other");
    const job = await targetJob();
    const pair = (resumeId: string) => `${base}/api/resumes/${resumeId}/target-jobs/${job.id}`;
    const score = record("POST /api/resumes/{id}/score", await post<JobAccepted>(`${base}/api/resumes/${resume.id}/score`));
    const fit = record("POST /api/resumes/{id}/target-jobs/{id}/fit", await post<JobAccepted>(`${pair(resume.id)}/fit`));
    const suggestions = record("POST /api/resumes/{id}/target-jobs/{id}/suggestions",
      await post<JobAccepted>(`${pair(resume.id)}/suggestions`));
    const otherSuggestions = await post<JobAccepted>(`${pair(other.id)}/suggestions`);
    const set = await createSet(resume.id, job.id);
    const setJobId = set.activeJob!.jobId;
    const recordedJobIds = [score.jobId, fit.jobId, suggestions.jobId, setJobId];
    await settle([...recordedJobIds, otherSuggestions.jobId]);
    for (const jobId of recordedJobIds) await readJob(jobId);

    const ready = record("GET /api/practice-sets/{id} (ready)", await readSet(set.id));
    const attempt = record("POST /api/practice-sets/{id}/questions/{id}/attempts",
      await post<Attempt>(`${base}/api/practice-sets/${set.id}/questions/${ready.questions[0].id}/attempts`, { text: "My first answer" }));
    await settle([attempt.activeJob!.jobId]);
    await readJob(attempt.activeJob!.jobId);

    const preview = record("GET /api/resumes/{id}/delete-impact", await get<DeleteImpact>(`${base}/api/resumes/${resume.id}/delete-impact`));
    expect(preview).toEqual({ scores: 1, fits: 1, suggestionSets: 1, practiceSets: 1, attempts: 1, staleSuggestionSets: 1 });
    record("GET /api/history", ownHistory(await get<History>(`${base}/api/history`), [resume.id, job.id, set.id]));

    await apiRequest(`${base}/api/resumes/${resume.id}`, { method: "DELETE" });
    expect(await errorOf(get(`${base}/api/resumes/${resume.id}`))).toMatchObject({ code: "RESUME_NOT_FOUND" });
    expect(await errorOf(readSet(set.id))).toMatchObject({ code: "PRACTICE_SET_NOT_FOUND" });
    expect(await errorOf(get(`${base}/api/jobs/${setJobId}`))).toMatchObject({ code: "JOB_NOT_FOUND" });
    const otherView = record("GET /api/resumes/{id}/target-jobs/{id}/suggestions (stale)", await get<SuggestionsView>(`${pair(other.id)}/suggestions`));
    expect(otherView.stale).toBe(true);
    const history = await get<History>(`${base}/api/history`);
    expect(ids(history.resumes)).toContain(other.id);
    expect(ids(history.resumes)).not.toContain(resume.id);
    expect(ids(history.practiceSets)).not.toContain(set.id);
    if (!base) {
      // The fake starts empty for each test, so it can still be held to the exact lists.
      expect(ids(history.resumes)).toEqual([other.id]);
      expect(history.practiceSets).toEqual([]);
    }
  });

  it("returns a practice set at once and fills 3-8 questions when its job succeeds", async () => {
    const resume = await paste("Backend");
    const job = await targetJob();
    const created = await createSet(resume.id, job.id);
    expect(created).toMatchObject({ status: "GENERATING", questions: [], activeJob: { jobType: "PRACTICE_QUESTIONS" } });

    await settle([created.activeJob!.jobId]);
    const ready = record("GET /api/practice-sets/{id} (ready)", await readSet(created.id));
    expect(ready.status).toBe("READY");
    expect(ready.questions.length).toBeGreaterThanOrEqual(3);
    expect(ready.questions.length).toBeLessThanOrEqual(8);
    for (const question of ready.questions) expect(question.rationale).toBeTruthy();
    const again = await createSet(resume.id, job.id, "POST /api/practice-sets (existing)");
    expect(again.id).toBe(created.id);
  });

  it("scores attempts, reports the change, and rejects an unchanged answer", async () => {
    const resume = await paste("Backend");
    const job = await targetJob();
    const set = await createSet(resume.id, job.id);
    await settle([set.activeJob!.jobId]);
    const question = (await readSet(set.id)).questions[0];
    const path = `${base}/api/practice-sets/${set.id}/questions/${question.id}/attempts`;

    const first = await post<Attempt>(path, { text: "Short answer" });
    await settle([first.activeJob!.jobId]);
    expect(await errorOf(post(path, { text: " Short answer " }))).toMatchObject({ status: 409, code: "ANSWER_UNCHANGED" });
    expect(await errorOf(post(path, { text: "   " }))).toMatchObject({ status: 400, code: "ANSWER_EMPTY" });

    const second = record("POST /api/practice-sets/{id}/questions/{id}/attempts",
      await post<Attempt>(path, { text: "A much longer answer with context, the action I took and a measured result." }));
    expect(second).toMatchObject({ number: 2, status: "PENDING" });
    await settle([second.activeJob!.jobId]);
    const attempts = record("GET /api/practice-sets/{id} (scored attempts)", await readSet(set.id)).questions[0].attempts;
    expect(attempts.map((attempt) => attempt.status)).toEqual(["SCORED", "SCORED"]);
    expect(attempts[1].scoreDelta).toBe(attempts[1].feedback!.score - attempts[0].feedback!.score);
  });

  it("marks the score stale when the job title changes", async () => {
    const resume = await paste("Backend");
    const score = record("POST /api/resumes/{id}/score", await post<JobAccepted>(`${base}/api/resumes/${resume.id}/score`));
    await settle([score.jobId]);
    const updated = record("PATCH /api/resumes/{id}", await apiRequest<Resume>(`${base}/api/resumes/${resume.id}`, {
      method: "PATCH", body: { jobTitle: "Staff Engineer" }
    }));
    expect(updated.latestScore?.stale).toBe(true);
  });

  // Runs last, after every scenario above has recorded its shapes. The fake run writes the file when it is missing;
  // `npm test -u` rewrites it after an intended contract change.
  it("answers with the response shapes in apiShapes.json", async () => {
    await expect(`${JSON.stringify(shapes, null, 2)}\n`).toMatchFileSnapshot("./apiShapes.json");
  });
}
