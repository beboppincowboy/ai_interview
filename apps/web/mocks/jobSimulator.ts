// Job state is derived from the stored creation time, not timers, so a job keeps advancing across reloads.
import type { ActiveJob, JobError, JobInputRefs, JobStage, JobStatus, JobType } from "@/lib/api/types";

export const MAX_ATTEMPTS = 3;

export const JOB_STAGES: Record<JobType, JobStage[]> = {
  RESUME_EXTRACTION: ["READING_FILE", "EXTRACTING_TEXT", "NORMALIZING_TEXT", "CHUNKING_TEXT"],
  RESUME_SCORE: ["SCORING_RESUME"],
  JOB_FIT: ["MATCHING_JOB"],
  EXPERIENCE_SUGGESTIONS: ["RETRIEVING_EXPERIENCE", "MATCHING_EXPERIENCE"],
  PRACTICE_QUESTIONS: ["GENERATING_QUESTIONS"],
  EXPERIENCE_SPLIT: ["SPLITTING_EXPERIENCE"],
  ANSWER_FEEDBACK: ["SCORING_ANSWER"],
  VOICE_REPORT: ["SCORING_ANSWER"]
};

export type JobFailure = { code: string; message: string; retryable: boolean };

export type MockJob = {
  id: string;
  type: JobType;
  createdAt: number;
  queuedMs: number;
  stageMs: number;
  refs: JobInputRefs;
  fail: JobFailure | null;
  /** Set once the job's effect has been written to its resource. */
  applied: boolean;
  result: unknown;
  /** Type-specific input the effect needs (for example the LinkedIn text). */
  payload?: Record<string, string>;
};

export type JobState = {
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  error: JobError | null;
  startedAt: number | null;
  completedAt: number | null;
};

export function jobState(job: MockJob, now: number): JobState {
  const stages = JOB_STAGES[job.type];
  const elapsed = now - job.createdAt;
  const startedAt = job.createdAt + job.queuedMs;
  if (elapsed < job.queuedMs) {
    return { status: "QUEUED", stage: "QUEUED", attempts: 0, error: null, startedAt: null, completedAt: null };
  }
  const running = elapsed - job.queuedMs;

  if (job.fail) {
    // Retryable: attempt 1, then a visible retry, then FAILED after the last attempt. Otherwise fail after attempt 1.
    const error = { code: job.fail.code, message: job.fail.message, retryable: job.fail.retryable };
    const stage = stages[0];
    if (running < job.stageMs) return { status: "PROCESSING", stage, attempts: 1, error: null, startedAt, completedAt: null };
    if (job.fail.retryable && running < job.stageMs * 2) {
      return { status: "RETRYING", stage, attempts: 2, error, startedAt, completedAt: null };
    }
    const failedAfter = job.stageMs * (job.fail.retryable ? 2 : 1);
    return {
      status: "FAILED",
      stage,
      attempts: job.fail.retryable ? MAX_ATTEMPTS : 1,
      error,
      startedAt,
      completedAt: startedAt + failedAfter
    };
  }

  const index = Math.floor(running / job.stageMs);
  if (index < stages.length) {
    return { status: "PROCESSING", stage: stages[index], attempts: 1, error: null, startedAt, completedAt: null };
  }
  return {
    status: "SUCCEEDED",
    stage: "COMPLETED",
    attempts: 1,
    error: null,
    startedAt,
    completedAt: startedAt + stages.length * job.stageMs
  };
}

export function activeJob(job: MockJob | undefined, now: number): ActiveJob | null {
  if (!job) return null;
  const { status, stage, attempts, error } = jobState(job, now);
  return { jobId: job.id, jobType: job.type, status, stage, attempts, maxAttempts: MAX_ATTEMPTS, error };
}
