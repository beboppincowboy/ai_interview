// Mock backend state implementing docs/api/frontend-api-contract.md. Section numbers (§) refer to it.
import type {
  Attempt,
  DeleteImpact,
  Experience,
  ExperienceBatchResult,
  ExperienceCreated,
  ExperienceInput,
  ExperienceSplitResult,
  ExperienceSuggestionsResult,
  FitView,
  History,
  JobAccepted,
  JobFitResult,
  JobInputRefs,
  JobStatusResponse,
  JobType,
  PasteResumeRequest,
  PracticeSet,
  Question,
  Resume,
  ResumeCreated,
  ResumeDetail,
  ResumeScoreResult,
  SuggestionSource,
  SuggestionsView,
  TargetJob,
  TargetJobCreated,
  TargetJobDetail,
  UpdateResumeRequest, VoiceSession, VoiceTranscript, VoiceSaveResult
} from "@/lib/api/types";
import * as fixtures from "./fixtures";
import { latestJob, jobState, type JobFailure, type MockJob } from "./jobSimulator";

export const STORAGE_KEY = "ai-interview:mock-db:v1";

export class MockHttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

type StoredResume = Omit<Resume, "latestScore" | "latestJob"> & {
  text: string | null;
  fileHash: string | null;
  scores: ResumeScoreResult[];
  jobId: string | null;
};
type StoredTargetJob = TargetJobDetail;
type StoredPairResult<T> = { resumeId: string; targetJobId: string; result: T | null; createdAt: string | null; jobId: string | null };
type StoredSuggestions = StoredPairResult<ExperienceSuggestionsResult> & { sourceIds: string[] };
type StoredAttempt = Omit<Attempt, "latestJob" | "scoreDelta"> & { jobId: string };
type StoredQuestion = Omit<Question, "attempts"> & { attempts: StoredAttempt[] };
type StoredSet = Omit<PracticeSet, "latestJob" | "questions"> & { questions: StoredQuestion[]; jobId: string | null };

type Db = {
  resumes: Record<string, StoredResume>;
  targetJobs: Record<string, StoredTargetJob>;
  experiences: Record<string, Experience>;
  fits: Record<string, StoredPairResult<JobFitResult>>;
  suggestions: Record<string, StoredSuggestions>;
  sets: Record<string, StoredSet>;
  jobs: Record<string, MockJob>;
  failNext: Partial<Record<JobType, JobFailure>>;
  voiceSessions: Record<string, VoiceSession>;
};

const emptyDb = (): Db => ({
  resumes: {}, targetJobs: {}, experiences: {}, fits: {}, suggestions: {}, sets: {}, jobs: {}, failNext: {}, voiceSessions: {}
});

export type MockStoreOptions = {
  /** Browser: localStorage, so data survives reloads and is shared by tabs. Tests: omit for memory only. */
  storage?: Pick<Storage, "getItem" | "setItem" | "removeItem">;
  now?: () => number;
  queuedMs?: number;
  stageMs?: number;
};

const pairKey = (resumeId: string, targetJobId: string) => `${resumeId}:${targetJobId}`;
const ALLOWED_EXTENSIONS = ["pdf", "doc", "docx", "txt", "text", "md", "markdown"];
const MAX_FILE_BYTES = 10 * 1024 * 1024;

function requireText(value: unknown, field: string, min: number, max: number): string {
  const text = typeof value === "string" ? value.trim() : "";
  if (text.length < min || text.length > max) {
    throw new MockHttpError(400, "INVALID_REQUEST", `${field} must be ${min}-${max} characters`);
  }
  return text;
}

function optionalText(value: unknown, field: string, max: number): string | null {
  if (value === undefined || value === null || (typeof value === "string" && value.trim() === "")) return null;
  return requireText(value, field, 1, max);
}

export function createMockStore(options: MockStoreOptions = {}) {
  const now = options.now ?? Date.now;
  const queuedMs = options.queuedMs ?? 400;
  const stageMs = options.stageMs ?? 1_200;
  let memory: Db = emptyDb();

  const iso = (ms = now()) => new Date(ms).toISOString();

  function load(): Db {
    if (!options.storage) return memory;
    try {
      const raw = options.storage.getItem(STORAGE_KEY);
      return raw ? { ...emptyDb(), ...(JSON.parse(raw) as Db) } : emptyDb();
    }
    catch {
      return emptyDb();
    }
  }

  function save(db: Db) {
    memory = db;
    options.storage?.setItem(STORAGE_KEY, JSON.stringify(db));
  }

  /** Every operation reads fresh state (other tabs may have written), settles finished jobs, then saves. */
  function tx<T>(work: (db: Db) => T): T {
    const db = load();
    settle(db);
    const result = work(db);
    save(db);
    return result;
  }

  // ---- jobs ------------------------------------------------------------------------------------

  function startJob(db: Db, type: JobType, refs: Partial<JobInputRefs>, payload?: Record<string, string>): MockJob {
    const job: MockJob = {
      id: crypto.randomUUID(),
      type,
      createdAt: now(),
      queuedMs,
      stageMs,
      refs: { resumeId: null, targetJobId: null, practiceSetId: null, attemptId: null, voiceSessionId: null, ...refs },
      fail: db.failNext[type] ?? null,
      applied: false,
      result: null,
      payload
    };
    delete db.failNext[type];
    db.jobs[job.id] = job;
    return job;
  }

  function accepted(job: MockJob): JobAccepted {
    const state = jobState(job, now());
    return {
      jobId: job.id,
      jobType: job.type,
      status: state.status,
      stage: state.stage,
      statusUrl: `/api/jobs/${job.id}`,
      reused: false,
      inputRefs: job.refs
    };
  }

  /** Writes the effect of every job whose simulated run has finished. */
  function settle(db: Db) {
    const at = now();
    for (const job of Object.values(db.jobs)) {
      if (job.applied) continue;
      const state = jobState(job, at);
      if (state.status !== "SUCCEEDED" && state.status !== "FAILED") continue;
      job.applied = true;
      const completedAt = iso(state.completedAt ?? at);
      if (state.status === "SUCCEEDED") job.result = applySuccess(db, job, completedAt);
      else applyFailure(db, job, completedAt);
    }
  }

  function applySuccess(db: Db, job: MockJob, completedAt: string): unknown {
    const { resumeId, targetJobId, practiceSetId, attemptId } = job.refs;
    const resume = resumeId ? db.resumes[resumeId] : undefined;
    const targetJob = targetJobId ? db.targetJobs[targetJobId] : undefined;
    switch (job.type) {
      case "RESUME_EXTRACTION": {
        if (!resume) return null;
        const text = job.payload?.text ?? "";
        const duplicate = Object.values(db.resumes).find((other) =>
          other.id !== resume.id && other.text !== null && fixtures.normalize(other.text) === fixtures.normalize(text));
        if (duplicate) {
          deleteResumeCascade(db, resume.id, job.id);
          return { resumeId: resume.id, duplicateOf: { id: duplicate.id, name: duplicate.name } };
        }
        Object.assign(resume, { text, status: "READY", updatedAt: completedAt });
        return { resumeId: resume.id, duplicateOf: null };
      }
      case "RESUME_SCORE": {
        if (!resume) return null;
        const result = fixtures.scoreResume(resume.text ?? "", resume.jobTitle, completedAt);
        resume.scores.push(result);
        resume.updatedAt = completedAt;
        return result;
      }
      case "JOB_FIT": {
        if (!resume || !targetJob) return null;
        const result = fixtures.jobFit(resume.text ?? "", targetJob.text);
        Object.assign(db.fits[pairKey(resume.id, targetJob.id)], { result, createdAt: completedAt });
        return result;
      }
      case "EXPERIENCE_SUGGESTIONS": {
        if (!resume || !targetJob) return null;
        const entry = db.suggestions[pairKey(resume.id, targetJob.id)];
        const sources = suggestionSources(db, resume.id);
        const result = fixtures.suggestions(sources);
        Object.assign(entry, { result, createdAt: completedAt, sourceIds: sources.map((s) => s.id).sort() });
        return result;
      }
      case "PRACTICE_QUESTIONS": {
        const set = practiceSetId ? db.sets[practiceSetId] : undefined;
        if (!set) return null;
        const aiQuestions = fixtures.practiceQuestions(db.targetJobs[set.targetJobId]?.text ?? "", () => crypto.randomUUID())
          .map((question) => ({ ...question, attempts: [] }));
        const userQuestions = set.questions.filter((question) => question.origin === "USER");
        set.questions = [...aiQuestions, ...userQuestions].map((question, index) => ({ ...question, order: index + 1 }));
        Object.assign(set, { status: "READY", updatedAt: completedAt });
        return { questions: set.questions.map((question) => publicQuestion(db, question)) };
      }
      case "EXPERIENCE_SPLIT": {
        const result: ExperienceSplitResult = {
          items: fixtures.splitLinkedIn(job.payload?.text ?? "").map((item) => {
            const existing = findExperienceDuplicate(db, item);
            return { ...item, duplicateOf: existing ? { id: existing.id, title: existing.title } : null };
          })
        };
        return result;
      }
      case "ANSWER_FEEDBACK": {
        const attempt = attemptId ? findAttempt(db, attemptId)?.attempt : undefined;
        if (!attempt) return null;
        attempt.feedback = fixtures.answerFeedback(attempt.text);
        attempt.status = "SCORED";
        return attempt.feedback;
      }
      case "VOICE_REPORT": {
        const session = job.refs.voiceSessionId ? db.voiceSessions[job.refs.voiceSessionId] : undefined;
        if (!session?.transcript || session.reportJobId !== job.id || session.report) return null;
        const answers = session.questions.flatMap((q) => {
          const answer = session.transcript!.answers.find((a) => a.questionId === q.id);
          return answer?.answerText ? [{ ...fixtures.answerFeedback(answer.answerText), questionId: q.id, incomplete: answer.incomplete }] : [];
        });
        session.report = {
          selectedCount: session.questions.length, answeredCount: answers.length,
          overallScore: Math.round(answers.reduce((sum, a) => sum + a.score, 0) / answers.length), answers,
          weakestQuestionIds: [...answers].sort((a, b) => a.score - b.score).slice(0, 3).map((a) => a.questionId),
          unansweredQuestionIds: session.questions.filter((q) => !answers.some((a) => a.questionId === q.id)).map((q) => q.id)
        };
        return session.report;
      }
    }
  }

  function applyFailure(db: Db, job: MockJob, completedAt: string) {
    const { resumeId, practiceSetId, attemptId } = job.refs;
    if (job.type === "RESUME_EXTRACTION" && resumeId && db.resumes[resumeId]) {
      Object.assign(db.resumes[resumeId], { status: "FAILED", updatedAt: completedAt });
    }
    if (job.type === "PRACTICE_QUESTIONS" && practiceSetId && db.sets[practiceSetId]) {
      Object.assign(db.sets[practiceSetId], { status: "FAILED", updatedAt: completedAt });
    }
    if (job.type === "ANSWER_FEEDBACK" && attemptId) {
      const found = findAttempt(db, attemptId);
      if (found) found.attempt.status = "FAILED";
    }
  }

  function getJob(jobId: string): JobStatusResponse {
    return tx((db) => {
      const job = db.jobs[jobId];
      if (!job) throw new MockHttpError(404, "JOB_NOT_FOUND", "Job not found");
      const state = jobState(job, now());
      return {
        jobId: job.id,
        jobType: job.type,
        status: state.status,
        stage: state.stage,
        attempts: state.attempts,
        maxAttempts: latestJob(job, now())!.maxAttempts,
        result: state.status === "SUCCEEDED" ? job.result : null,
        error: state.error,
        createdAt: iso(job.createdAt),
        startedAt: state.startedAt === null ? null : iso(state.startedAt),
        completedAt: state.completedAt === null ? null : iso(state.completedAt),
        inputRefs: job.refs
      };
    });
  }

  const jobOf = (db: Db, jobId: string | null) => latestJob(jobId ? db.jobs[jobId] : undefined, now());

  // ---- resumes (§3) ----------------------------------------------------------------------------

  function publicResume(db: Db, resume: StoredResume): Resume {
    const latest = resume.scores.at(-1);
    return {
      id: resume.id,
      name: resume.name,
      jobTitle: resume.jobTitle,
      source: resume.source,
      originalFilename: resume.originalFilename,
      status: resume.status,
      latestScore: latest
        ? { overall: latest.overall, scoredAt: latest.scoredAt, stale: latest.jobTitle !== resume.jobTitle }
        : null,
      latestJob: jobOf(db, resume.jobId),
      createdAt: resume.createdAt,
      updatedAt: resume.updatedAt
    };
  }

  function requireResume(db: Db, id: string) {
    const resume = db.resumes[id];
    if (!resume) throw new MockHttpError(404, "RESUME_NOT_FOUND", "Resume not found");
    return resume;
  }

  function requireReadyResume(db: Db, id: string) {
    const resume = requireResume(db, id);
    if (resume.status !== "READY") throw new MockHttpError(409, "RESUME_NOT_READY", "Resume is not ready");
    return resume;
  }

  function newResume(fields: Pick<StoredResume, "name" | "jobTitle" | "source" | "originalFilename" | "status" | "text" | "fileHash">): StoredResume {
    const at = iso();
    return { ...fields, id: crypto.randomUUID(), scores: [], jobId: null, createdAt: at, updatedAt: at };
  }

  function uploadResume(input: { fileName: string; size: number; content: string; name?: string | null; jobTitle?: string | null }):
    { status: 200 | 202; body: ResumeCreated } {
    return tx((db) => {
      const extension = input.fileName.includes(".") ? input.fileName.split(".").pop()!.toLowerCase() : "";
      if (!ALLOWED_EXTENSIONS.includes(extension)) {
        throw new MockHttpError(415, "UNSUPPORTED_FILE_TYPE", "Resume must be a PDF, DOC, DOCX, TXT, or Markdown file");
      }
      if (input.size > MAX_FILE_BYTES) throw new MockHttpError(413, "UPLOAD_TOO_LARGE", "The file exceeds 10 MB");
      const name = optionalText(input.name, "name", 80) ?? input.fileName.replace(/\.[^.]+$/, "").slice(0, 80);
      const jobTitle = optionalText(input.jobTitle, "jobTitle", 100);
      const fileHash = String(fixtures.hash(input.content));
      const sameFile = Object.values(db.resumes).find((resume) => resume.fileHash === fileHash);
      if (sameFile) return { status: 200, body: { resume: publicResume(db, sameFile), duplicate: true } };

      const isText = ["txt", "text", "md", "markdown"].includes(extension);
      const text = isText ? input.content : fixtures.sampleResumeText(`${input.fileName} (${fileHash})`);
      const resume = newResume({
        name, jobTitle, source: "UPLOAD", originalFilename: input.fileName, status: "PROCESSING", text: null, fileHash
      });
      db.resumes[resume.id] = resume;
      resume.jobId = startJob(db, "RESUME_EXTRACTION", { resumeId: resume.id }, { text }).id;
      return { status: 202, body: { resume: publicResume(db, resume), duplicate: false } };
    });
  }

  function pasteResume(input: Partial<PasteResumeRequest>): { status: 200 | 201; body: ResumeCreated } {
    return tx((db) => {
      const name = requireText(input.name, "name", 1, 80);
      const jobTitle = optionalText(input.jobTitle, "jobTitle", 100);
      const text = requireText(input.text, "text", 100, 50_000);
      const duplicate = Object.values(db.resumes).find((resume) =>
        resume.text !== null && fixtures.normalize(resume.text) === fixtures.normalize(text));
      if (duplicate) return { status: 200, body: { resume: publicResume(db, duplicate), duplicate: true } };
      const resume = newResume({ name, jobTitle, source: "PASTE", originalFilename: null, status: "READY", text, fileHash: null });
      db.resumes[resume.id] = resume;
      return { status: 201, body: { resume: publicResume(db, resume), duplicate: false } };
    });
  }

  const listResumes = () => tx((db) => ({
    items: Object.values(db.resumes).sort(newestFirst).map((resume) => publicResume(db, resume))
  }));

  const getResume = (id: string): ResumeDetail => tx((db) => {
    const resume = requireResume(db, id);
    return { ...publicResume(db, resume), text: resume.text, score: resume.scores.at(-1) ?? null };
  });

  const updateResume = (id: string, input: UpdateResumeRequest): Resume => tx((db) => {
    const resume = requireResume(db, id);
    if (input.name !== undefined) resume.name = requireText(input.name, "name", 1, 80);
    if (input.jobTitle !== undefined) resume.jobTitle = optionalText(input.jobTitle, "jobTitle", 100);
    resume.updatedAt = iso();
    return publicResume(db, resume);
  });

  const scoreResume = (id: string): JobAccepted => tx((db) => {
    const resume = requireReadyResume(db, id);
    const job = startJob(db, "RESUME_SCORE", { resumeId: id });
    resume.jobId = job.id;
    return accepted(job);
  });

  // ---- target jobs (§4) ------------------------------------------------------------------------

  const publicTargetJob = ({ id, name, createdAt, updatedAt }: StoredTargetJob): TargetJob => ({ id, name, createdAt, updatedAt });

  function requireTargetJob(db: Db, id: string) {
    const targetJob = db.targetJobs[id];
    if (!targetJob) throw new MockHttpError(404, "TARGET_JOB_NOT_FOUND", "Target job not found");
    return targetJob;
  }

  const createTargetJob = (input: { name?: unknown; text?: unknown }): { status: 200 | 201; body: TargetJobCreated } => tx((db) => {
    const name = requireText(input.name, "name", 1, 80);
    const text = requireText(input.text, "text", 100, 20_000);
    const duplicate = Object.values(db.targetJobs).find((job) => fixtures.normalize(job.text) === fixtures.normalize(text));
    if (duplicate) return { status: 200, body: { targetJob: duplicate, duplicate: true } };
    const at = iso();
    const targetJob = { id: crypto.randomUUID(), name, text, createdAt: at, updatedAt: at };
    db.targetJobs[targetJob.id] = targetJob;
    return { status: 201, body: { targetJob, duplicate: false } };
  });

  const listTargetJobs = () => tx((db) => ({ items: Object.values(db.targetJobs).sort(newestFirst).map(publicTargetJob) }));
  const getTargetJob = (id: string): TargetJobDetail => tx((db) => requireTargetJob(db, id));
  const renameTargetJob = (id: string, name: unknown): TargetJob => tx((db) => {
    const targetJob = requireTargetJob(db, id);
    Object.assign(targetJob, { name: requireText(name, "name", 1, 80), updatedAt: iso() });
    return publicTargetJob(targetJob);
  });

  // ---- experiences (§5) ------------------------------------------------------------------------

  function validateExperience(input: Partial<ExperienceInput>): ExperienceInput {
    const month = (value: unknown, field: string) => {
      const text = optionalText(value, field, 7);
      if (text !== null && !/^\d{4}-\d{2}$/.test(text)) throw new MockHttpError(400, "INVALID_REQUEST", `${field} must be YYYY-MM`);
      return text;
    };
    return {
      title: requireText(input.title, "title", 1, 120),
      organization: optionalText(input.organization, "organization", 120),
      startDate: month(input.startDate, "startDate"),
      endDate: month(input.endDate, "endDate"),
      description: requireText(input.description, "description", 1, 4_000)
    };
  }

  const experienceKey = (item: ExperienceInput) => fixtures.normalize(item.title) + "|" + fixtures.normalize(item.description);
  const findExperienceDuplicate = (db: Db, item: ExperienceInput) =>
    Object.values(db.experiences).find((existing) => experienceKey(existing) === experienceKey(item));

  function requireExperience(db: Db, id: string) {
    const experience = db.experiences[id];
    if (!experience) throw new MockHttpError(404, "EXPERIENCE_NOT_FOUND", "Experience not found");
    return experience;
  }

  function addExperience(db: Db, item: ExperienceInput, source: Experience["source"]): Experience {
    const experience = { ...item, id: crypto.randomUUID(), source, createdAt: iso() };
    db.experiences[experience.id] = experience;
    return experience;
  }

  const createExperience = (input: Partial<ExperienceInput>): { status: 200 | 201; body: ExperienceCreated } => tx((db) => {
    const item = validateExperience(input);
    const duplicate = findExperienceDuplicate(db, item);
    if (duplicate) return { status: 200, body: { experience: duplicate, duplicate: true } };
    return { status: 201, body: { experience: addExperience(db, item, "FORM"), duplicate: false } };
  });

  const splitLinkedIn = (text: unknown): JobAccepted => tx((db) =>
    accepted(startJob(db, "EXPERIENCE_SPLIT", {}, { text: requireText(text, "text", 50, 20_000) })));

  const saveExperienceBatch = (items: unknown): ExperienceBatchResult => tx((db) => {
    if (!Array.isArray(items) || items.length < 1 || items.length > 30) {
      throw new MockHttpError(400, "INVALID_REQUEST", "items must contain 1-30 experiences");
    }
    const result: ExperienceBatchResult = { created: [], skipped: [] };
    for (const item of items.map(validateExperience)) {
      const existing = findExperienceDuplicate(db, item);
      if (existing) result.skipped.push({ title: item.title, existingId: existing.id, existingTitle: existing.title });
      else result.created.push(addExperience(db, item, "LINKEDIN"));
    }
    return result;
  });

  const listExperiences = () => tx((db) => ({ items: Object.values(db.experiences).sort(newestFirst) }));
  const renameExperience = (id: string, title: unknown): Experience => tx((db) => {
    const experience = requireExperience(db, id);
    experience.title = requireText(title, "title", 1, 120);
    return experience;
  });

  // ---- fit and suggestions (§6) ----------------------------------------------------------------

  function suggestionSources(db: Db, resumeId: string): SuggestionSource[] {
    return [
      ...Object.values(db.resumes)
        .filter((resume) => resume.id !== resumeId && resume.status === "READY")
        .map((resume) => ({ type: "RESUME" as const, id: resume.id, name: resume.name })),
      ...Object.values(db.experiences).map((experience) => ({ type: "EXPERIENCE" as const, id: experience.id, name: experience.title }))
    ];
  }

  function requirePair(db: Db, resumeId: string, targetJobId: string) {
    const resume = requireResume(db, resumeId);
    requireTargetJob(db, targetJobId);
    return resume;
  }

  function fitEntry(db: Db, resumeId: string, targetJobId: string) {
    return (db.fits[pairKey(resumeId, targetJobId)] ??= { resumeId, targetJobId, result: null, createdAt: null, jobId: null });
  }

  function suggestionsEntry(db: Db, resumeId: string, targetJobId: string) {
    return (db.suggestions[pairKey(resumeId, targetJobId)] ??=
      { resumeId, targetJobId, result: null, createdAt: null, jobId: null, sourceIds: [] });
  }

  const getFit = (resumeId: string, targetJobId: string): FitView => tx((db) => {
    requirePair(db, resumeId, targetJobId);
    const entry = db.fits[pairKey(resumeId, targetJobId)];
    return {
      resumeId,
      targetJobId,
      result: entry?.result ?? null,
      createdAt: entry?.createdAt ?? null,
      latestJob: jobOf(db, entry?.jobId ?? null)
    };
  });

  const runFit = (resumeId: string, targetJobId: string): JobAccepted => tx((db) => {
    requirePair(db, resumeId, targetJobId);
    requireReadyResume(db, resumeId);
    const job = startJob(db, "JOB_FIT", { resumeId, targetJobId });
    fitEntry(db, resumeId, targetJobId).jobId = job.id;
    return accepted(job);
  });

  const getSuggestions = (resumeId: string, targetJobId: string): SuggestionsView => tx((db) => {
    requirePair(db, resumeId, targetJobId);
    const entry = db.suggestions[pairKey(resumeId, targetJobId)];
    const currentIds = suggestionSources(db, resumeId).map((source) => source.id).sort();
    const result = entry?.result
      ? { items: entry.result.items.filter((item) => currentIds.includes(item.source.id)) }
      : null;
    return {
      resumeId,
      targetJobId,
      sourcesAvailable: currentIds.length > 0,
      stale: Boolean(entry?.result) && entry.sourceIds.join() !== currentIds.join(),
      result,
      createdAt: entry?.createdAt ?? null,
      latestJob: jobOf(db, entry?.jobId ?? null)
    };
  });

  const runSuggestions = (resumeId: string, targetJobId: string): JobAccepted => tx((db) => {
    requirePair(db, resumeId, targetJobId);
    requireReadyResume(db, resumeId);
    if (suggestionSources(db, resumeId).length === 0) {
      throw new MockHttpError(409, "NO_EXPERIENCE_SOURCES", "Add another resume or an experience first");
    }
    const job = startJob(db, "EXPERIENCE_SUGGESTIONS", { resumeId, targetJobId });
    suggestionsEntry(db, resumeId, targetJobId).jobId = job.id;
    return accepted(job);
  });

  // ---- practice (§7) ---------------------------------------------------------------------------

  function publicAttempts(db: Db, attempts: StoredAttempt[]): Attempt[] {
    let previousScore: number | null = null;
    return attempts.map(({ jobId, ...attempt }) => {
      const score = attempt.status === "SCORED" ? attempt.feedback?.score ?? null : null;
      const scoreDelta = score !== null && previousScore !== null ? score - previousScore : null;
      if (score !== null) previousScore = score;
      return { ...attempt, scoreDelta, latestJob: jobOf(db, jobId) };
    });
  }

  const publicQuestion = (db: Db, question: StoredQuestion): Question =>
    ({ ...question, attempts: publicAttempts(db, question.attempts) });

  function publicSet(db: Db, set: StoredSet): PracticeSet {
    const { jobId, questions, ...rest } = set;
    return { ...rest, questions: questions.map((question) => publicQuestion(db, question)), latestJob: jobOf(db, jobId) };
  }

  function requireSet(db: Db, id: string) {
    const set = db.sets[id];
    if (!set) throw new MockHttpError(404, "PRACTICE_SET_NOT_FOUND", "Practice set not found");
    return set;
  }

  function findAttempt(db: Db, attemptId: string) {
    for (const set of Object.values(db.sets)) {
      for (const question of set.questions) {
        const attempt = question.attempts.find((candidate) => candidate.id === attemptId);
        if (attempt) return { set, question, attempt };
      }
    }
    return undefined;
  }

  const createPracticeSet = (input: { resumeId?: unknown; targetJobId?: unknown; mode?: unknown }):
    { status: 200 | 201; body: PracticeSet } => tx((db) => {
    if (input.mode !== "PRACTICE") throw new MockHttpError(400, "INVALID_REQUEST", "mode must be PRACTICE");
    const resumeId = String(input.resumeId ?? "");
    const targetJobId = String(input.targetJobId ?? "");
    requirePair(db, resumeId, targetJobId);
    const existing = Object.values(db.sets).find((set) => set.resumeId === resumeId && set.targetJobId === targetJobId);
    if (existing) return { status: 200, body: publicSet(db, existing) };
    requireReadyResume(db, resumeId);
    const at = iso();
    const set: StoredSet = {
      id: crypto.randomUUID(), resumeId, targetJobId, mode: "PRACTICE", status: "GENERATING",
      questions: [], jobId: null, createdAt: at, updatedAt: at
    };
    db.sets[set.id] = set;
    set.jobId = startJob(db, "PRACTICE_QUESTIONS", { resumeId, targetJobId, practiceSetId: set.id }).id;
    return { status: 201, body: publicSet(db, set) };
  });

  const getPracticeSet = (id: string): PracticeSet => tx((db) => publicSet(db, requireSet(db, id)));

  const retryPracticeSet = (id: string): PracticeSet => tx((db) => {
    const set = requireSet(db, id);
    if (set.status !== "FAILED") throw new MockHttpError(409, "PRACTICE_SET_NOT_FAILED", "Practice set did not fail");
    set.status = "GENERATING";
    set.jobId = startJob(db, "PRACTICE_QUESTIONS", { resumeId: set.resumeId, targetJobId: set.targetJobId, practiceSetId: set.id }).id;
    return publicSet(db, set);
  });

  const addQuestion = (setId: string, text: unknown): Question => tx((db) => {
    const set = requireSet(db, setId);
    if (set.status === "GENERATING") throw new MockHttpError(409, "PRACTICE_SET_NOT_READY", "Questions are still being generated");
    const questionText = requireText(text, "text", 10, 500);
    if (set.questions.filter((question) => question.origin === "USER").length >= 10) {
      throw new MockHttpError(409, "QUESTION_LIMIT_REACHED", "A practice set can have at most 10 of your own questions");
    }
    const question: StoredQuestion = {
      id: crypto.randomUUID(), order: set.questions.length + 1, origin: "USER", text: questionText,
      rationale: null, category: null, expectedSignals: [], attempts: []
    };
    set.questions.push(question);
    set.updatedAt = iso();
    return publicQuestion(db, question);
  });

  const submitAttempt = (setId: string, questionId: string, text: unknown): Attempt => tx((db) => {
    const set = requireSet(db, setId);
    const question = set.questions.find((candidate) => candidate.id === questionId);
    if (!question) throw new MockHttpError(404, "QUESTION_NOT_FOUND", "Question not found");
    const answer = typeof text === "string" ? text.trim() : "";
    if (!answer) throw new MockHttpError(400, "ANSWER_EMPTY", "The answer is empty");
    if (answer.length > 4_000) throw new MockHttpError(400, "ANSWER_TOO_LONG", "The answer is over 4,000 characters");
    if (question.attempts.at(-1)?.text === answer) {
      throw new MockHttpError(409, "ANSWER_UNCHANGED", "The answer is the same as your last attempt");
    }
    const id = crypto.randomUUID();
    const job = startJob(db, "ANSWER_FEEDBACK", { resumeId: set.resumeId, targetJobId: set.targetJobId, practiceSetId: set.id, attemptId: id });
    question.attempts.push({
      id, number: question.attempts.length + 1, text: answer, status: "PENDING", feedback: null, jobId: job.id, createdAt: iso()
    });
    set.updatedAt = iso();
    return publicAttempts(db, question.attempts).at(-1)!;
  });

  const retryAttempt = (attemptId: string): Attempt => tx((db) => {
    const found = findAttempt(db, attemptId);
    if (!found) throw new MockHttpError(404, "ATTEMPT_NOT_FOUND", "Attempt not found");
    const { set, question, attempt } = found;
    if (attempt.status !== "FAILED") throw new MockHttpError(409, "ATTEMPT_NOT_FAILED", "Attempt did not fail");
    attempt.status = "PENDING";
    attempt.jobId = startJob(db, "ANSWER_FEEDBACK", {
      resumeId: set.resumeId, targetJobId: set.targetJobId, practiceSetId: set.id, attemptId
    }).id;
    return publicAttempts(db, question.attempts).find((candidate) => candidate.id === attemptId)!;
  });

  // ---- voice sessions ------------------------------------------------------------------------

  function requireVoice(db: Db, id: string) {
    const session = db.voiceSessions[id];
    if (!session) throw new MockHttpError(404, "VOICE_SESSION_NOT_FOUND", "Interview session was not found");
    if (session.status !== "SAVED" && Date.parse(session.draftExpiresAt) <= now()) session.status = "EXPIRED";
    return session;
  }

  const createVoiceSession = (practiceSetId: unknown): VoiceSession => tx((db) => {
    const set = requireSet(db, String(practiceSetId ?? ""));
    const questions = set.questions.filter((q) => q.origin === "AI").slice(0, 6).map(({ id, text, category, expectedSignals }) => ({ id, text, category, expectedSignals }));
    if (!questions.length) throw new MockHttpError(409, "PRACTICE_SET_NOT_READY", "Practice questions are not ready");
    const session: VoiceSession = {
      id: crypto.randomUUID(), practiceSetId: set.id, resumeId: set.resumeId, targetJobId: set.targetJobId,
      status: "DRAFT", questions, transcript: null, submissionJobId: null, reportJobId: null, report: null,
      createdAt: iso(), runDeadline: iso(now() + 20 * 60_000), draftExpiresAt: iso(now() + 24 * 60 * 60_000), savedAt: null
    };
    db.voiceSessions[session.id] = session;
    return session;
  });

  const getVoiceSession = (id: string) => tx((db) => requireVoice(db, id));
  const saveVoiceSession = (id: string, input: unknown): VoiceSaveResult => tx((db) => {
    const session = requireVoice(db, id);
    const supplied = (input as Partial<VoiceTranscript> | null)?.answers;
    if (!Array.isArray(supplied)) throw new MockHttpError(400, "INVALID_REQUEST", "answers is required");
    const seen = new Set<string>();
    const answers = supplied.map((answer) => {
      if (!answer || !session.questions.some((q) => q.id === answer.questionId) || seen.has(answer.questionId) || typeof answer.answerText !== "string" || typeof answer.interviewerText !== "string" || typeof answer.incomplete !== "boolean") throw new MockHttpError(400, "INVALID_REQUEST", "Invalid transcript answer");
      seen.add(answer.questionId);
      const answerText = answer.answerText.trim();
      if (answerText.length > 4_000) throw new MockHttpError(400, "ANSWER_TOO_LONG", "answerText must be at most 4000 characters");
      return { questionId: answer.questionId, interviewerText: answer.interviewerText.trim(), answerText, incomplete: answer.incomplete };
    }).sort((a, b) => session.questions.findIndex((q) => q.id === a.questionId) - session.questions.findIndex((q) => q.id === b.questionId));
    if (!answers.some((a) => a.answerText)) throw new MockHttpError(400, "ANSWER_EMPTY", "At least one answer must not be blank");
    const transcript = { answers };
    const normalized = JSON.stringify(transcript);
    if (new TextEncoder().encode(normalized).length > 64 * 1024) throw new MockHttpError(400, "TRANSCRIPT_TOO_LARGE", "The transcript is too large");
    if (session.transcript) {
      if (JSON.stringify(session.transcript) !== normalized) throw new MockHttpError(409, "VOICE_SESSION_ALREADY_SAVED", "This interview was already saved");
      return { session, replayed: true };
    }
    if (session.status === "EXPIRED") throw new MockHttpError(409, "VOICE_SESSION_EXPIRED", "The interview draft expired before it was saved");
    const job = startJob(db, "VOICE_REPORT", { resumeId: session.resumeId, targetJobId: session.targetJobId, voiceSessionId: id });
    Object.assign(session, { status: "SAVED", transcript, savedAt: iso(), submissionJobId: job.id, reportJobId: job.id });
    return { session, replayed: false };
  });

  const retryVoiceReport = (id: string) => tx((db) => {
    const session = requireVoice(db, id);
    if (session.status !== "SAVED") throw new MockHttpError(404, "VOICE_SESSION_NOT_FOUND", "Interview session was not found");
    if (session.report) return session;
    const old = session.reportJobId ? db.jobs[session.reportJobId] : undefined;
    if (!old || jobState(old, now()).status !== "FAILED") throw new MockHttpError(409, "VOICE_REPORT_NOT_RETRYABLE", "Only a failed current report can be retried");
    session.reportJobId = startJob(db, "VOICE_REPORT", { resumeId: session.resumeId, targetJobId: session.targetJobId, voiceSessionId: id }).id;
    return session;
  });

  const deleteVoiceSession = (id: string, draftOnly = false) => tx((db) => {
    const session = requireVoice(db, id);
    if (draftOnly && session.status === "SAVED") throw new MockHttpError(409, "VOICE_SESSION_ALREADY_SAVED", "This interview was already saved");
    removeJobs(db, (job) => job.refs.voiceSessionId === id);
    delete db.voiceSessions[id];
  });

  // ---- deletes (§3.6-3.7, §4.5-4.6, §5.6-5.7) ------------------------------------------------

  function pairsMatching(db: Db, match: (resumeId: string, targetJobId: string) => boolean) {
    const fits = Object.entries(db.fits).filter(([, entry]) => match(entry.resumeId, entry.targetJobId));
    const suggestionSets = Object.entries(db.suggestions).filter(([, entry]) => match(entry.resumeId, entry.targetJobId));
    const sets = Object.values(db.sets).filter((set) => match(set.resumeId, set.targetJobId));
    return { fits, suggestionSets, sets };
  }

  function impact(db: Db, match: (resumeId: string, targetJobId: string) => boolean, sourceId: string | null, scores: number): DeleteImpact {
    const { fits, suggestionSets, sets } = pairsMatching(db, match);
    return {
      scores,
      voiceSessions: Object.values(db.voiceSessions).filter((v) => v.status === "SAVED" && match(v.resumeId, v.targetJobId)).length,
      fits: fits.filter(([, entry]) => entry.result).length,
      suggestionSets: suggestionSets.filter(([, entry]) => entry.result).length,
      practiceSets: sets.length,
      attempts: sets.reduce((total, set) => total + set.questions.reduce((sum, question) => sum + question.attempts.length, 0), 0),
      staleSuggestionSets: sourceId === null ? 0 : Object.values(db.suggestions)
        .filter((entry) => !match(entry.resumeId, entry.targetJobId) && entry.sourceIds.includes(sourceId)).length
    };
  }

  function removePairs(db: Db, match: (resumeId: string, targetJobId: string) => boolean) {
    const { fits, suggestionSets, sets } = pairsMatching(db, match);
    for (const [key] of fits) delete db.fits[key];
    for (const [key] of suggestionSets) delete db.suggestions[key];
    for (const set of sets) delete db.sets[set.id];
    for (const session of Object.values(db.voiceSessions)) if (match(session.resumeId, session.targetJobId)) delete db.voiceSessions[session.id];
  }

  function removeJobs(db: Db, match: (job: MockJob) => boolean, keepJobId?: string) {
    for (const job of Object.values(db.jobs)) if (job.id !== keepJobId && match(job)) delete db.jobs[job.id];
  }

  function deleteResumeCascade(db: Db, id: string, keepJobId?: string) {
    delete db.resumes[id];
    removePairs(db, (resumeId) => resumeId === id);
    removeJobs(db, (job) => job.refs.resumeId === id, keepJobId);
  }

  const resumeImpact = (id: string) => tx((db) =>
    impact(db, (resumeId) => resumeId === id, id, requireResume(db, id).scores.length));
  const deleteResume = (id: string) => tx((db) => {
    requireResume(db, id);
    deleteResumeCascade(db, id);
  });

  const targetJobImpact = (id: string) => tx((db) => {
    requireTargetJob(db, id);
    return impact(db, (_, targetJobId) => targetJobId === id, null, 0);
  });
  const deleteTargetJob = (id: string) => tx((db) => {
    requireTargetJob(db, id);
    delete db.targetJobs[id];
    removePairs(db, (_, targetJobId) => targetJobId === id);
    removeJobs(db, (job) => job.refs.targetJobId === id);
  });

  const experienceImpact = (id: string) => tx((db) => {
    requireExperience(db, id);
    return impact(db, () => false, id, 0);
  });
  const deleteExperience = (id: string) => tx((db) => {
    requireExperience(db, id);
    delete db.experiences[id];
  });

  // ---- history (§9) ----------------------------------------------------------------------------

  const history = (): History => tx((db) => ({
    voiceSessions: Object.values(db.voiceSessions).filter((v) => v.status === "SAVED")
      .sort((a, b) => b.savedAt!.localeCompare(a.savedAt!)).map((v) => ({
        id: v.id, practiceSetId: v.practiceSetId, resumeId: v.resumeId, resumeName: db.resumes[v.resumeId].name,
        targetJobId: v.targetJobId, targetJobName: db.targetJobs[v.targetJobId].name, savedAt: v.savedAt!,
        selectedCount: v.questions.length, answeredCount: v.transcript!.answers.filter((a) => a.answerText.trim()).length,
        overallScore: v.report?.overallScore ?? null, reportJobId: v.reportJobId!,
        reportStatus: v.report ? "SUCCEEDED" : (v.reportJobId && db.jobs[v.reportJobId] ? jobState(db.jobs[v.reportJobId], now()).status : "FAILED")
      })),
    resumes: Object.values(db.resumes).sort(newestFirst).map((resume) => ({
      id: resume.id,
      name: resume.name,
      scores: resume.scores.map(({ overall, scoredAt }) => ({ overall, scoredAt }))
    })),
    targetJobs: Object.values(db.targetJobs).sort(newestFirst).map((targetJob) => ({
      id: targetJob.id,
      name: targetJob.name,
      fits: Object.values(db.fits)
        .filter((entry) => entry.targetJobId === targetJob.id && entry.result && db.resumes[entry.resumeId])
        .map((entry) => ({
          resumeId: entry.resumeId,
          resumeName: db.resumes[entry.resumeId].name,
          fitScore: entry.result!.fitScore,
          createdAt: entry.createdAt!
        }))
    })),
    practiceSets: Object.values(db.sets)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .map((set) => ({
        id: set.id,
        resumeId: set.resumeId,
        resumeName: db.resumes[set.resumeId]?.name ?? "",
        targetJobId: set.targetJobId,
        targetJobName: db.targetJobs[set.targetJobId]?.name ?? "",
        updatedAt: set.updatedAt,
        questions: set.questions.map((question) => ({
          id: question.id,
          text: question.text,
          scores: question.attempts.flatMap((attempt) => attempt.status === "SCORED" && attempt.feedback ? [attempt.feedback.score] : [])
        }))
      }))
  }));

  // ---- test and console controls ---------------------------------------------------------------

  /** Forces the next job of `type` to fail. Retryable failures show a retry before failing. */
  function failNext(type: JobType, { retryable = true, code }: { retryable?: boolean; code?: string } = {}) {
    tx((db) => {
      db.failNext[type] = {
        code: code ?? (retryable ? "GEMINI_TIMEOUT" : "GEMINI_SAFETY"),
        message: "Simulated failure",
        retryable
      };
    });
  }

  function reset() {
    memory = emptyDb();
    options.storage?.removeItem(STORAGE_KEY);
  }

  return {
    getJob,
    uploadResume, pasteResume, listResumes, getResume, updateResume, scoreResume, resumeImpact, deleteResume,
    createTargetJob, listTargetJobs, getTargetJob, renameTargetJob, targetJobImpact, deleteTargetJob,
    createExperience, splitLinkedIn, saveExperienceBatch, listExperiences, renameExperience, experienceImpact, deleteExperience,
    getFit, runFit, getSuggestions, runSuggestions,
    createPracticeSet, getPracticeSet, retryPracticeSet, addQuestion, submitAttempt, retryAttempt,
    createVoiceSession, getVoiceSession, saveVoiceSession, retryVoiceReport, deleteVoiceSession,
    history,
    failNext,
    reset
  };
}

export type MockStore = ReturnType<typeof createMockStore>;

function newestFirst(a: { createdAt: string }, b: { createdAt: string }) {
  return b.createdAt.localeCompare(a.createdAt);
}
