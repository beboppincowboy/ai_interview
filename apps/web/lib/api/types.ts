// Platform types shared by the API client and the job hooks.
// Resource types below mirror docs/api/frontend-api-contract.md; section numbers refer to it.

/** Error response body: nullable machine-readable code plus required message. */
export type ApiErrorBody = { code: string | null; message: string };

/** Supported background-job kinds. */
export type JobType =
  | "RESUME_EXTRACTION"
  | "RESUME_SCORE"
  | "JOB_FIT"
  | "EXPERIENCE_SUGGESTIONS"
  | "PRACTICE_QUESTIONS"
  | "EXPERIENCE_SPLIT"
  | "ANSWER_FEEDBACK"
  | "VOICE_REPORT";

/** Background-job lifecycle states. */
export type JobStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "SUCCEEDED" | "FAILED";

/** Background-job progress stages. */
export type JobStage =
  | "QUEUED"
  | "READING_FILE"
  | "EXTRACTING_TEXT"
  | "NORMALIZING_TEXT"
  | "CHUNKING_TEXT"
  | "SCORING_RESUME"
  | "MATCHING_JOB"
  | "RETRIEVING_EXPERIENCE"
  | "MATCHING_EXPERIENCE"
  | "GENERATING_QUESTIONS"
  | "SPLITTING_EXPERIENCE"
  | "SCORING_ANSWER"
  | "COMPLETED";

/** Job failure detail; `retryable` says whether the server may run the job again. */
export type JobError = { code: string | null; message: string; retryable: boolean | null };

/** Nullable references to the resources a background job uses. */
export type JobInputRefs = {
  resumeId: string | null;
  targetJobId: string | null;
  practiceSetId: string | null;
  attemptId: string | null;
  voiceSessionId: string | null;
};

/** Newest resource job in any status; includes identity, stage, attempts, and error. Null means none was submitted; check status before showing progress. */
export type LatestJob = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  maxAttempts: number;
  error: JobError | null;
};

/** Job submission response with status URL, input references, and whether an active matching job was reused. */
export type JobAccepted = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  statusUrl: string;
  reused: boolean;
  inputRefs: JobInputRefs;
};

/** Polled job state; `result` may be checkpoint data before success and is final only when status is SUCCEEDED. */
export type JobStatusResponse<TResult = unknown> = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  maxAttempts: number;
  result: TResult | null;
  error: JobError | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  inputRefs: JobInputRefs;
};

export function isTerminal(status: JobStatus | undefined) {
  return status === "SUCCEEDED" || status === "FAILED";
}

/** Codes with mapped UI messages plus client timeout; unknown and dynamic exhausted-job codes use generic copy. */
export type ErrorCode =
  | "INVALID_REQUEST" | "ANSWER_EMPTY" | "ANSWER_TOO_LONG" | "TRANSCRIPT_TOO_LARGE" | "RESUME_REFERENCE_REQUIRED"
  | "NOT_FOUND" | "RESUME_NOT_FOUND" | "TARGET_JOB_NOT_FOUND" | "EXPERIENCE_NOT_FOUND" | "PRACTICE_SET_NOT_FOUND"
  | "QUESTION_NOT_FOUND" | "ATTEMPT_NOT_FOUND" | "JOB_NOT_FOUND" | "CONFLICT" | "EXPERIENCE_DUPLICATE" | "RESUME_NOT_READY"
  | "NO_EXPERIENCE_SOURCES" | "PRACTICE_SET_NOT_READY" | "PRACTICE_SET_NOT_FAILED" | "QUESTION_LIMIT_REACHED"
  | "ANSWER_UNCHANGED" | "ATTEMPT_NOT_FAILED" | "UPLOAD_TOO_LARGE" | "UNSUPPORTED_FILE_TYPE"
  | "UNPROCESSABLE_CONTENT" | "RESUME_EXTRACTION_FAILED" | "RATE_LIMITED" | "SERVICE_UNAVAILABLE"
  | "RESUME_PARSER_BUSY" | "INTERNAL_ERROR" | "REQUEST_FAILED" | "STORAGE_UNAVAILABLE" | "PROCESSING_ERROR"
  | "REQUEST_TIMEOUT"
  | "GEMINI_NOT_CONFIGURED" | "GEMINI_RATE_LIMITED" | "GEMINI_TIMEOUT" | "GEMINI_UPSTREAM_ERROR" | "GEMINI_SAFETY"
  | "GEMINI_RECITATION" | "GEMINI_MAX_TOKENS" | "GEMINI_EMPTY_RESPONSE" | "GEMINI_INVALID_RESPONSE"
  | "VOICE_DISABLED" | "VOICE_SESSION_NOT_FOUND" | "VOICE_SESSION_EXPIRED" | "VOICE_SESSION_NOT_DRAFT"
  | "VOICE_QUESTION_NOT_FOUND" | "VOICE_RUN_EXPIRED" | "VOICE_TOKEN_BUDGET_EXHAUSTED"
  | "VOICE_TOKEN_TIMEOUT" | "VOICE_TOKEN_RATE_LIMITED" | "VOICE_TOKEN_UNAVAILABLE"
  | "VOICE_SESSION_ALREADY_SAVED" | "VOICE_REPORT_NOT_RETRYABLE";

/** Priority values used on resume fixes and job-fit feedback. */
export type Priority = "HIGH" | "MEDIUM" | "LOW";

// §1.1
/** Seven downstream counts affected by deleting a resume or target job; non-applicable counts are zero. */
export type DeleteImpact = {
  scores: number;
  fits: number;
  suggestionSets: number;
  practiceSets: number;
  attempts: number;
  staleSuggestionSets: number;
  voiceSessions: number;
};

/** API collection wrapper; this endpoint shape contains items and no paging metadata. */
export type Page<T> = { items: T[] };

// §3 Resumes
/** Current resume score, scoring timestamp, and whether its job title has changed since scoring. */
export type ScoreSummary = { overall: number; scoredAt: string; stale: boolean };

/** Resume library row with nullable job title, filename, score, and latest job. */
export type Resume = {
  id: string;
  name: string;
  jobTitle: string | null;
  source: "UPLOAD" | "PASTE";
  originalFilename: string | null;
  status: "PROCESSING" | "READY" | "FAILED";
  latestScore: ScoreSummary | null;
  /** Newest background job for this resource, in any status (check `status`); null only if none was ever submitted. */
  latestJob: LatestJob | null;
  createdAt: string;
  updatedAt: string;
};

/** Resume row plus nullable extracted text and full score result. */
export type ResumeDetail = Resume & { text: string | null; score: ResumeScoreResult | null };
/** Creation response; duplicate true returns the existing resume unchanged. */
export type ResumeCreated = { resume: Resume; duplicate: boolean };
/** Pasted-resume input; name is 1–80 chars and text is 100–50,000 chars. */
export type PasteResumeRequest = { name: string; jobTitle: string | null; text: string };
/** Partial resume update; omitted fields stay unchanged and `jobTitle: null` clears the title. */
export type UpdateResumeRequest = { name?: string; jobTitle?: string | null };

// §4 Target jobs
/** Target-job library row with ID, name, and creation/update timestamps. */
export type TargetJob = { id: string; name: string; createdAt: string; updatedAt: string };
/** Target-job row plus full posting text. */
export type TargetJobDetail = TargetJob & { text: string };
/** Creation response; duplicate true returns the existing target job unchanged. */
export type TargetJobCreated = { targetJob: TargetJobDetail; duplicate: boolean };
/** Target-job input; name is 1–80 chars and posting text is 100–20,000 chars. */
export type CreateTargetJobRequest = { name: string; text: string };

// §5 Experiences
/** Experience create input; organization and dates may be null. */
export type ExperienceInput = {
  title: string;
  organization: string | null;
  startDate: string | null;
  endDate: string | null;
  description: string;
};
/** Saved experience with ID, source, and creation timestamp. */
export type Experience = ExperienceInput & { id: string; source: "FORM" | "LINKEDIN"; createdAt: string };
/** Create response; duplicate true returns the existing item matched by normalized title and description. */
export type ExperienceCreated = { experience: Experience; duplicate: boolean };
/** `duplicates` are submitted items that matched a saved experience and were not stored. */
export type ExperienceBatchResult = {
  created: Experience[];
  duplicates: { title: string; existingId: string; existingTitle: string }[];
};

// §6 Fit and suggestions
/** Saved fit for a resume/target-job pair; result and createdAt (UTC) are null before a result exists. */
export type FitView = {
  resumeId: string;
  targetJobId: string;
  result: JobFitResult | null;
  createdAt: string | null;
  /** Newest background job for this resource, in any status (check `status`); null only if none was ever submitted. */
  latestJob: LatestJob | null;
  /** Current server emits UNKNOWN; omitted on older servers also means unknown. Source-change evaluation is not implemented yet. */
  freshness?: JobFitFreshness;
};

/** Suggestion state for a resume/target-job pair; stale means the current source set changed. */
export type SuggestionsView = {
  resumeId: string;
  targetJobId: string;
  sourcesAvailable: boolean;
  stale: boolean;
  result: ExperienceSuggestionsResult | null;
  createdAt: string | null;
  /** Newest background job for this resource, in any status (check `status`); null only if none was ever submitted. */
  latestJob: LatestJob | null;
};

// §7 Practice
/** `duplicate` is true when the pair already had a set; `practiceSet` is that set and no job started. */
export type PracticeSetCreated = { practiceSet: PracticeSet; duplicate: boolean };
/** Pair-owned practice set with lifecycle state, ordered questions, and latest job. */
export type PracticeSet = {
  id: string;
  resumeId: string;
  targetJobId: string;
  mode: "PRACTICE";
  status: "GENERATING" | "READY" | "FAILED";
  questions: Question[];
  /** Newest background job for this resource, in any status (check `status`); null only if none was ever submitted. */
  latestJob: LatestJob | null;
  createdAt: string;
  updatedAt: string;
};

/** Practice question; attempts are ordered oldest first and `order` is its display position. */
export type Question = {
  id: string;
  order: number;
  origin: "AI" | "USER";
  text: string;
  rationale: string | null;
  category: string | null;
  expectedSignals: string[];
  attempts: Attempt[];
};

/** Submitted answer; status and score delta are derived from feedback and its latest job. */
export type Attempt = {
  id: string;
  number: number;
  text: string;
  status: "PENDING" | "SCORED" | "FAILED";
  feedback: AnswerFeedbackResult | null;
  scoreDelta: number | null;
  /** Newest background job for this resource, in any status (check `status`); null only if none was ever submitted. */
  latestJob: LatestJob | null;
  createdAt: string;
};

/** Practice-question snapshot copied into a spoken interview. */
export type VoiceQuestion = { id: string; text: string; category: string | null; expectedSignals: string[] };
/** Reviewed transcript answer with original interviewer speech and candidate-corrected answer. */
export type VoiceAnswer = { questionId: string; interviewerText: string; answerText: string; incomplete: boolean };
/** Reviewed answers, one for each reached question. */
export type VoiceTranscript = { answers: VoiceAnswer[] };
/** Per-answer feedback plus its question ID and incomplete-capture flag. */
export type VoiceAnswerReport = AnswerFeedbackResult & { questionId: string; incomplete: boolean };
/** Saved-session report with answer counts, 0–100 overall score, and weakest/unanswered question IDs. */
export type VoiceReport = {
  selectedCount: number;
  answeredCount: number;
  overallScore: number;
  answers: VoiceAnswerReport[];
  weakestQuestionIds: string[];
  unansweredQuestionIds: string[];
};
/** Spoken-interview session snapshot, transcript, initial/current report job IDs, expiry times, and nullable report. */
export type VoiceSession = {
  id: string;
  practiceSetId: string;
  resumeId: string;
  targetJobId: string;
  status: "DRAFT" | "EXPIRED" | "SAVED";
  questions: VoiceQuestion[];
  transcript: VoiceTranscript | null;
  submissionJobId: string | null;
  reportJobId: string | null;
  createdAt: string;
  runDeadline: string;
  draftExpiresAt: string;
  savedAt: string | null;
  report: VoiceReport | null;
};
/** `duplicate` is true when the same transcript was already saved; `session` is that save and no new report job started. */
export type VoiceSaveResult = { session: VoiceSession; duplicate: boolean };
/** Short-lived provider token returned to the client; it is not stored on the session. */
export type VoiceToken = { token: string; model: string; apiVersion: "v1beta"; expiresAt: string; newSessionExpiresAt: string };

// §8.2 Job results
/** Resume score with 0–100 component scores, fixes, exact-line rewrites, and scoring time. */
export type ResumeScoreResult = {
  overall: number;
  scores: { technicalDepth: number; impact: number; clarity: number; relevance: number; ats: number };
  summary: string;
  fixes: { rank: number; section: string; priority: Priority; message: string }[];
  rewrites: { section: string; original: string; rewritten: string; placeholders: string[] }[];
  jobTitle: string | null;
  scoredAt: string;
};

/** Job-fit evidence/guidance and feedback; agentic scores are validated as 0–100, legacy decoding preserves historical values. */
export type JobFitResult = {
  fitScore: number;
  summary: string;
  matchedRequirements: { requirement: string; evidence: string }[];
  missingRequirements: { requirement: string; guidance: string }[];
  feedback: { priority: Priority; message: string }[];
  /** Absent or null for legacy one-pass results. */
  agenticMetadata?: JobFitEvidenceMetadata | null;
};

/** Strength of recorded support; NOT_ESTABLISHED describes a documentation gap, not lack of candidate ability. */
export type JobFitEvidenceStatus = "SUPPORTED" | "PARTIALLY_SUPPORTED" | "NOT_ESTABLISHED";
/** CURRENT matches current sources; OUTDATED has changed sources; UNKNOWN offers no freshness guarantee. */
export type JobFitFreshness = "CURRENT" | "OUTDATED" | "UNKNOWN";
export type JobFitSourceType = "RESUME" | "TARGET_JOB" | "EXPERIENCE";
export type JobFitJudgmentState = "JUDGED" | "UNCHECKED";
export type JobFitTraceKind = "RETRIEVAL" | "JUDGMENT" | "FALLBACK" | "VERIFICATION";

/** Frozen source identity (resourceId is a UUID); contentHash is non-blank with no prescribed encoding. */
export type JobFitSourceManifestEntry = {
  sourceType: JobFitSourceType;
  resourceId: string;
  sourceLabel: string;
  contentHash: string;
};

/** RAW source text, not HTML-escaped; quote is an exact substring of passage, not a semantic support guarantee.
 * id is snapshot-local; snapshotId matches metadata, and source identity/hash/label match its manifest.
 */
export type JobFitEvidenceItem = {
  id: string;
  snapshotId: string;
  sourceType: JobFitSourceType;
  resourceId: string;
  contentHash: string;
  sourceLabel: string;
  passage: string;
  quote: string;
};

/** Catalog references: targetJobEvidenceIds define the requirement; evidenceIds cite resume/experience support.
 * Positive statuses require evidence; non-SUPPORTED statuses require a gap. Citing any conflict member requires all its evidence IDs and its conflictId.
 */
export type JobFitRequirementAssessment = {
  requirementId: string;
  requirement: string;
  status: JobFitEvidenceStatus;
  targetJobEvidenceIds: string[];
  evidenceIds: string[];
  conflictIds: string[];
  missingEvidenceDescription: string | null;
};

/** Catalog references to at least two distinct candidate source records; an associated assessment cannot be SUPPORTED. */
export type JobFitEvidenceConflict = { id: string; description: string; evidenceIds: string[] };

/** Non-blank provider/model/rubric identity, independent of confidence and evidence status. */
export type JobFitJudgmentProvenance = { provider: string; model: string; rubricVersion: string };

/** One judgment per assessment; JUDGED has status/provenance and the same candidate evidence set (order may differ).
 * UNCHECKED has null status/provenance, empty evidence/probabilities, and accompanies NOT_ESTABLISHED.
 * Probabilities are optional per status, finite 0..1 values; they need not sum to one or determine status.
 */
export type JobFitJudgment = {
  requirementId: string;
  state: JobFitJudgmentState;
  provenance: JobFitJudgmentProvenance | null;
  status: JobFitEvidenceStatus | null;
  evidenceIds: string[];
  missingEvidence: string[];
  providerProbabilities: Partial<Record<JobFitEvidenceStatus, number>>;
};

/** User-facing summary excluding private reasoning by caller policy; no HTML escaping. round is null or a non-negative integer. */
export type JobFitTraceEvent = { kind: JobFitTraceKind; summary: string; round: number | null };

/** Version 1 server-validated source snapshot; TypeScript declarations perform no runtime validation.
 * Includes exactly one resume/target job; catalog IDs are unique, while citations may be reused across assessments (see API contract §8.2).
 * fallbackReason is non-blank iff fallbackUsed, otherwise null; source evidence retains RAW customer text.
 */
export type JobFitEvidenceMetadata = {
  schemaVersion: 1;
  snapshotId: string;
  sourceManifest: JobFitSourceManifestEntry[];
  assessments: JobFitRequirementAssessment[];
  evidenceCatalog: JobFitEvidenceItem[];
  conflicts: JobFitEvidenceConflict[];
  judgments: JobFitJudgment[];
  trace: JobFitTraceEvent[];
  fallbackUsed: boolean;
  fallbackReason: string | null;
};

/** Resume or experience that supplied evidence for a suggestion. */
export type SuggestionSource = { type: "RESUME" | "EXPERIENCE"; id: string; name: string };

/** Generated requirement matches, each tied to one source with evidence and presentation guidance. */
export type ExperienceSuggestionsResult = {
  items: { requirement: string; source: SuggestionSource; match: string; whyItFits: string; guidance: string }[];
};

/** Proposed split item; `duplicateOf` identifies a saved experience match when present. */
export type ExperienceDraft = ExperienceInput & { duplicateOf: { id: string; title: string } | null };
/** Result of splitting pasted experience text into proposed items. */
export type ExperienceSplitResult = { items: ExperienceDraft[] };

/** Extraction completion result; duplicate resumes are removed and identified by `duplicateOf`. */
export type ResumeExtractionResult = { resumeId: string; duplicateOf: { id: string; name: string } | null };

/** Scored answer feedback; score is 0–100 and next step/follow-up may be null. */
export type AnswerFeedbackResult = {
  score: number;
  summary: string;
  nextStep: string | null;
  strengths: string[];
  gaps: string[];
  betterAnswerOutline: string[];
  followUpQuestion: string | null;
};

// §9 History
/** Saved-result history groups; score/fitting trends are oldest first in service output. */
export type History = {
  voiceSessions: VoiceSessionHistory[];
  resumes: { id: string; name: string; scores: { overall: number; scoredAt: string }[] }[];
  targetJobs: {
    id: string;
    name: string;
    fits: { resumeId: string; resumeName: string; fitScore: number; createdAt: string }[];
  }[];
  practiceSets: {
    id: string;
    resumeId: string;
    resumeName: string;
    targetJobId: string;
    targetJobName: string;
    updatedAt: string;
    questions: { id: string; text: string; scores: number[] }[];
  }[];
};

/** Saved voice-session summary including answer counts, optional overall score, and report job status. */
export type VoiceSessionHistory = {
  id: string; practiceSetId: string; resumeId: string; resumeName: string;
  targetJobId: string; targetJobName: string; savedAt: string;
  selectedCount: number; answeredCount: number; overallScore: number | null;
  reportJobId: string; reportStatus: JobStatus;
};
