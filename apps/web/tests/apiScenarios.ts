// API scenarios shared by the mock API (mocks.test.ts) and a real API (liveApi.test.ts, opt-in).
// They hold on a persistent, shared database: every pasted text is unique, and lists are checked for what they contain
// or omit rather than compared whole. Every response they read is recorded as a shape and compared with one committed
// file, so a field that one side renames or drops fails the run against that side.
import { afterAll, expect, it } from "vitest";
import { apiRequest } from "@/lib/api/client";
import type {
  Attempt, DeleteImpact, History, JobAccepted, JobStatusResponse, PracticeSet, Resume, ResumeCreated, SuggestionsView,
  TargetJobCreated
} from "@/lib/api/types";

export type ApiTarget = {
  /** Prefix for every request path: empty for the mock API, an origin such as http://127.0.0.1:3000 for a real API. */
  base: string;
  /** Resolves once every listed job has finished. */
  settle: (jobIds: string[]) => Promise<void>;
};

export const post = <T,>(path: string, body: unknown = {}) => apiRequest<T>(path, { method: "POST", body, retries: 0 });
export const get = <T,>(path: string) => apiRequest<T>(path, { retries: 0 });
export const errorOf = (promise: Promise<unknown>) => promise.then(() => null, (error: unknown) => error);

/** A real API's `settle`: polls each job until it ends. A failed job means the API broke at runtime, not contract drift.
 * Each job gets `timeoutMs`, so a job that never ends fails the run with its ID instead of hanging until vitest's timeout. */
export const pollJobs = (base: string, { timeoutMs = 120_000, intervalMs = 1_000 } = {}) => async (jobIds: string[]) => {
  for (const jobId of jobIds) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const job = await get<JobStatusResponse>(`${base}/api/jobs/${jobId}`);
      if (job.status === "SUCCEEDED") break;
      if (job.status === "FAILED") {
        throw new Error(`Runtime failure, not a contract disagreement: ${job.jobType} job ${jobId} failed with ${job.error?.code ?? "no error code"}`);
      }
      if (Date.now() >= deadline) {
        throw new Error(`${job.jobType} job ${jobId} was still ${job.status} after ${timeoutMs / 1_000}s`);
      }
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
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

// These AI result arrays can be empty, but their stable item fields remain part of the API contract.
const OPTIONAL_ARRAY_FIELDS: Record<string, string[]> = {
  "result.feedback": ["message: string", "priority: string"],
  "result.items": [
    "guidance: string",
    "match: string",
    "requirement: string",
    "source",
    "source.id: string",
    "source.name: string",
    "source.type: string",
    "whyItFits: string"
  ],
  "result.matchedRequirements": ["evidence: string", "requirement: string"],
  "result.missingRequirements": ["guidance: string", "requirement: string"],
  "result.rewrites": ["original: string", "placeholders", "rewritten: string", "section: string"]
};

const sameFields = (a: string[], b: string[]) => a.length === b.length && a.every((field, index) => field === b[index]);

/** Key paths of a JSON value, with the type of each value that is not an object or list (`path: string`). A list
 * contributes its first item, as `path[]`. `populated` collects declared optional arrays that arrived with items,
 * so a run can tell real item shapes from the declared fallback. */
export function shapeOf(value: unknown, path = "", populated?: Set<string>): string[] {
  if (Array.isArray(value)) {
    const declaredFields = OPTIONAL_ARRAY_FIELDS[path];
    if (declaredFields) {
      if (value.length) populated?.add(path);
      const expected = declaredFields.map((field) => `${path}[].${field}`).sort();
      for (const item of value) {
        const observed = shapeOf(item, `${path}[]`).sort();
        if (!sameFields(observed, expected)) {
          throw new Error(`Unexpected item shape at ${path}: expected ${expected.join(", ")}; got ${observed.join(", ")}`);
        }
      }
      return expected;
    }
    return value.length ? shapeOf(value[0], `${path}[]`, populated) : [];
  }
  if (value === null || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, child]) => {
    const childPath = path ? `${path}.${key}` : key;
    if (child === null || typeof child !== "object") return [`${childPath}: ${child === null ? "null" : typeof child}`];
    return [childPath, ...shapeOf(child, childPath, populated)];
  });
}

/** Records a response shape under its scenario label. */
export function recordShape<T>(shapes: Record<string, string[]>, name: string, body: T, populated?: Set<string>): T {
  const shape = shapeOf(body, "", populated).sort();
  const previous = shapes[name];
  if (previous) {
    if (!sameFields(previous, shape)) {
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
  const populated = new Set<string>();
  const record = <T,>(name: string, body: T) => recordShape(shapes, name, body, populated);
  // Shapes are compared only after every scenario passed, so a filtered or failed run cannot rewrite apiShapes.json
  // with part of the contract under `-u`.
  let registered = 0;
  let passed = 0;
  const scenario = (name: string, run: () => Promise<void>) => {
    registered += 1;
    it(name, async () => {
      await run();
      passed += 1;
    });
  };

  // A real API keeps what a run creates, so the run deletes its resumes and target jobs (and with them their
  // scores, fits, suggestions and practice sets) when it ends. The mock API is reset after every test.
  const created = { resumes: new Set<string>(), targetJobs: new Set<string>() };
  afterAll(async () => {
    if (!base) return;
    const paths = [
      ...[...created.resumes].map((id) => `${base}/api/resumes/${id}`),
      ...[...created.targetJobs].map((id) => `${base}/api/target-jobs/${id}`)
    ];
    for (const path of paths) {
      const error = await errorOf(apiRequest(path, { method: "DELETE", retries: 0 }));
      if (error && (error as { status?: number }).status !== 404) throw error;
    }
  });

  const paste = async (name: string, text = resumeText()) => {
    const resume = record("POST /api/resumes/paste (new)",
      await post<ResumeCreated>(`${base}/api/resumes/paste`, { name, jobTitle: null, text })).resume;
    created.resumes.add(resume.id);
    return resume;
  };
  const targetJob = async () => {
    const job = record("POST /api/target-jobs (new)",
      await post<TargetJobCreated>(`${base}/api/target-jobs`, { name: "Acme", text: jobText() })).targetJob;
    created.targetJobs.add(job.id);
    return job;
  };
  const createSet = async (resumeId: string, targetJobId: string, label = "POST /api/practice-sets (new)") => record(label,
    await post<PracticeSet>(`${base}/api/practice-sets`, { resumeId, targetJobId, mode: "PRACTICE" }));
  const readSet = (id: string) => get<PracticeSet>(`${base}/api/practice-sets/${id}`);
  const readJob = async (jobId: string) => {
    const job = await get<JobStatusResponse>(`${base}/api/jobs/${jobId}`);
    return record(`GET /api/jobs/{id} (${job.jobType})`, job);
  };

  scenario("resolves a pasted duplicate to the saved resume", async () => {
    const text = resumeText();
    const saved = await paste("First", text);
    const again = record("POST /api/resumes/paste (duplicate)",
      await post<ResumeCreated>(`${base}/api/resumes/paste`, { name: "Second", jobTitle: null, text: `  ${text}  ` }));
    expect(again).toMatchObject({ duplicate: true, resume: { id: saved.id, name: "First" } });
  });

  scenario("previews and cascades a resume delete", async () => {
    const resume = await paste("Backend");
    // Evidence the selected resume lacks, so a real model has a strong match and returns suggestion items.
    const other = await paste("Other", resumeText().replace("Worked on backend services.",
      "Built Kafka event streaming pipelines and deployed them on Kubernetes."));
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
    // The second suggestions job is read too, so suggestion items get two chances to arrive non-empty.
    for (const jobId of [...recordedJobIds, otherSuggestions.jobId]) await readJob(jobId);

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
      // The mock API starts empty for each test, so it can still be held to the exact lists.
      expect(ids(history.resumes)).toEqual([other.id]);
      expect(history.practiceSets).toEqual([]);
    }
  });

  scenario("returns a practice set at once and fills 3-8 questions when its job succeeds", async () => {
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

  scenario("scores attempts, reports the change, and rejects an unchanged answer", async () => {
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

  scenario("marks the score stale when the job title changes", async () => {
    const resume = await paste("Backend");
    const score = record("POST /api/resumes/{id}/score", await post<JobAccepted>(`${base}/api/resumes/${resume.id}/score`));
    await settle([score.jobId]);
    await readJob(score.jobId); // A second chance for resume rewrites to arrive non-empty.
    const updated = record("PATCH /api/resumes/{id}", await apiRequest<Resume>(`${base}/api/resumes/${resume.id}`, {
      method: "PATCH", body: { jobTitle: "Staff Engineer" }
    }));
    expect(updated.latestScore?.stale).toBe(true);
  });

  // Runs last, after every scenario above has recorded its shapes. The mock API run writes the file when it is missing;
  // `npm test -u` rewrites it after an intended contract change.
  it("answers with the response shapes in apiShapes.json", async () => {
    if (passed < registered) throw new Error(`Shapes are compared after every scenario passes; ${passed} of ${registered} did`);
    // An empty declared array records its declared fields, not the API's, so a real API must fill each one at least once.
    if (base) expect([...populated].sort()).toEqual(Object.keys(OPTIONAL_ARRAY_FIELDS).sort());
    await expect(`${JSON.stringify(shapes, null, 2)}\n`).toMatchFileSnapshot("./apiShapes.json");
  });
}
