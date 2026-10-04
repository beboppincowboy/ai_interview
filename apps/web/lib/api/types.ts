// Platform types shared by the API client and the job hooks.
// Resource types below mirror docs/api/frontend-api-contract.md; section numbers refer to it.

export type ApiErrorBody = { code: string | null; message: string };

export type JobType =
  | "RESUME_EXTRACTION"
  | "RESUME_SCORE"
  | "JOB_FIT"
  | "EXPERIENCE_SUGGESTIONS"
  | "PRACTICE_QUESTIONS"
  | "EXPERIENCE_SPLIT"
  | "ANSWER_FEEDBACK"
  | "VOICE_REPORT";

export type JobStatus = "QUEUED" | "PROCESSING" | "RETRYING" | "SUCCEEDED" | "FAILED";

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

export type JobError = { code: string | null; message: string; retryable: boolean | null };

export type JobInputRefs = {
  resumeId: string | null;
  targetJobId: string | null;
  practiceSetId: string | null;
  attemptId: string | null;
  voiceSessionId: string | null;
};

export type ActiveJob = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  attempts: number;
  maxAttempts: number;
  error: JobError | null;
};

export type JobAccepted = {
  jobId: string;
  jobType: JobType;
  status: JobStatus;
  stage: JobStage;
  statusUrl: string;
  reused: boolean;
  inputRefs: JobInputRefs;
};

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

// §10 error codes the UI handles (the backend may add more; unknown codes fall back to generic copy).
export type ErrorCode =
  | "INVALID_REQUEST" | "ANSWER_EMPTY" | "ANSWER_TOO_LONG" | "NOT_FOUND"
  | "RESUME_NOT_FOUND" | "TARGET_JOB_NOT_FOUND" | "EXPERIENCE_NOT_FOUND" | "PRACTICE_SET_NOT_FOUND"
  | "QUESTION_NOT_FOUND" | "ATTEMPT_NOT_FOUND" | "JOB_NOT_FOUND" | "CONFLICT" | "RESUME_NOT_READY"
  | "NO_EXPERIENCE_SOURCES" | "PRACTICE_SET_NOT_READY" | "PRACTICE_SET_NOT_FAILED" | "QUESTION_LIMIT_REACHED"
  | "ANSWER_UNCHANGED" | "ATTEMPT_NOT_FAILED" | "UPLOAD_TOO_LARGE" | "UNSUPPORTED_FILE_TYPE"
  | "UNPROCESSABLE_CONTENT" | "RESUME_EXTRACTION_FAILED" | "RATE_LIMITED" | "SERVICE_UNAVAILABLE"
  | "RESUME_PARSER_BUSY" | "INTERNAL_ERROR" | "REQUEST_FAILED"
  | "VOICE_DISABLED" | "VOICE_SESSION_NOT_FOUND" | "VOICE_SESSION_EXPIRED" | "VOICE_SESSION_NOT_DRAFT"
  | "VOICE_QUESTION_NOT_FOUND" | "VOICE_RUN_EXPIRED" | "VOICE_TOKEN_BUDGET_EXHAUSTED"
  | "VOICE_TOKEN_TIMEOUT" | "VOICE_TOKEN_RATE_LIMITED" | "VOICE_TOKEN_UNAVAILABLE"
  | "VOICE_SESSION_ALREADY_SAVED" | "VOICE_REPORT_NOT_RETRYABLE" | "TRANSCRIPT_TOO_LARGE";

export type Priority = "HIGH" | "MEDIUM" | "LOW";

// §1.1
export type DeleteImpact = {
  scores: number;
  fits: number;
  suggestionSets: number;
  practiceSets: number;
  attempts: number;
  staleSuggestionSets: number;
};

export type Page<T> = { items: T[] };

// §3 Resumes
export type ScoreSummary = { overall: number; scoredAt: string; stale: boolean };

export type Resume = {
  id: string;
  name: string;
  jobTitle: string | null;
  source: "UPLOAD" | "PASTE";
  originalFilename: string | null;
  status: "PROCESSING" | "READY" | "FAILED";
  latestScore: ScoreSummary | null;
  activeJob: ActiveJob | null;
  createdAt: string;
  updatedAt: string;
};

export type ResumeDetail = Resume & { text: string | null; score: ResumeScoreResult | null };
export type ResumeCreated = { resume: Resume; duplicate: boolean };
export type PasteResumeRequest = { name: string; jobTitle: string | null; text: string };
export type UpdateResumeRequest = { name?: string; jobTitle?: string | null };

// §4 Target jobs
export type TargetJob = { id: string; name: string; createdAt: string; updatedAt: string };
export type TargetJobDetail = TargetJob & { text: string };
export type TargetJobCreated = { targetJob: TargetJobDetail; duplicate: boolean };
export type CreateTargetJobRequest = { name: string; text: string };

// §5 Experiences
export type ExperienceInput = {
  title: string;
  organization: string | null;
  startDate: string | null;
  endDate: string | null;
  description: string;
};
export type Experience = ExperienceInput & { id: string; source: "FORM" | "LINKEDIN"; createdAt: string };
export type ExperienceCreated = { experience: Experience; duplicate: boolean };
export type ExperienceBatchResult = {
  created: Experience[];
  skipped: { title: string; existingId: string; existingTitle: string }[];
};

// §6 Fit and suggestions
export type FitView = {
  resumeId: string;
  targetJobId: string;
  result: JobFitResult | null;
  createdAt: string | null;
  activeJob: ActiveJob | null;
};

export type SuggestionsView = {
  resumeId: string;
  targetJobId: string;
  sourcesAvailable: boolean;
  stale: boolean;
  result: ExperienceSuggestionsResult | null;
  createdAt: string | null;
  activeJob: ActiveJob | null;
};

// §7 Practice
export type PracticeSet = {
  id: string;
  resumeId: string;
  targetJobId: string;
  mode: "PRACTICE";
  status: "GENERATING" | "READY" | "FAILED";
  questions: Question[];
  activeJob: ActiveJob | null;
  createdAt: string;
  updatedAt: string;
};

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

export type Attempt = {
  id: string;
  number: number;
  text: string;
  status: "PENDING" | "SCORED" | "FAILED";
  feedback: AnswerFeedbackResult | null;
  scoreDelta: number | null;
  activeJob: ActiveJob | null;
  createdAt: string;
};

export type VoiceQuestion = { id: string; text: string; category: string | null; expectedSignals: string[] };
export type VoiceAnswer = { questionId: string; interviewerText: string; answerText: string; incomplete: boolean };
export type VoiceTranscript = { answers: VoiceAnswer[] };
export type VoiceAnswerReport = AnswerFeedbackResult & { questionId: string; incomplete: boolean };
export type VoiceReport = {
  selectedCount: number;
  answeredCount: number;
  overallScore: number;
  answers: VoiceAnswerReport[];
  weakestQuestionIds: string[];
  unansweredQuestionIds: string[];
};
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
export type VoiceSaveResult = { session: VoiceSession; replayed: boolean };
export type VoiceToken = { token: string; model: string; apiVersion: "v1beta"; expiresAt: string; newSessionExpiresAt: string };

// §8.2 Job results
export type ResumeScoreResult = {
  overall: number;
  scores: { technicalDepth: number; impact: number; clarity: number; relevance: number; ats: number };
  summary: string;
  fixes: { rank: number; section: string; priority: Priority; message: string }[];
  rewrites: { section: string; original: string; rewritten: string; placeholders: string[] }[];
  jobTitle: string | null;
  scoredAt: string;
};

export type JobFitResult = {
  fitScore: number;
  summary: string;
  matchedRequirements: { requirement: string; evidence: string }[];
  missingRequirements: { requirement: string; guidance: string }[];
  feedback: { priority: Priority; message: string }[];
};

export type SuggestionSource = { type: "RESUME" | "EXPERIENCE"; id: string; name: string };

export type ExperienceSuggestionsResult = {
  items: { requirement: string; source: SuggestionSource; match: string; whyItFits: string; guidance: string }[];
};

export type ExperienceDraft = ExperienceInput & { duplicateOf: { id: string; title: string } | null };
export type ExperienceSplitResult = { items: ExperienceDraft[] };

export type ResumeExtractionResult = { resumeId: string; duplicateOf: { id: string; name: string } | null };

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
export type History = {
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
